'use strict';

const crypto = require('crypto');

const {
  firstNonEmpty,
  parsePositiveInteger
} = require('./config');
const { httpError } = require('./presence');

const DEFAULT_EASYTIER_SESSION_TTL_SECONDS = 90;
const MIN_EASYTIER_SESSION_TTL_SECONDS = 60;
const MAX_EASYTIER_SESSION_TTL_SECONDS = 24 * 60 * 60;
const DEFAULT_LAN_ROOM_LIST_LIMIT = 20;
const MAX_LAN_ROOM_LIST_LIMIT = 50;
const MAX_LAN_ROOM_MEMBERS = 16;
const MAX_LAN_ROOM_MEMBERS_PER_SOURCE_IP = 8;
const MAX_LAN_ROOMS_PER_SOURCE_IP = 10;
const MAX_ID_LENGTH = 128;
const MAX_TEXT_LENGTH = 256;
const MAX_LAN_ROOM_DESCRIPTION_LENGTH = 120;
const MAX_LAN_KICK_MESSAGE_LENGTH = 160;
const MAX_LAN_REPORTED_MODS = 128;
const MAX_LAN_REPORTED_MOD_NAME_LENGTH = 160;
const LAN_STATIC_IPV4_PREFIX_LENGTH = 24;
const LAN_STATIC_IPV4_SECOND_OCTET = 126;
const LAN_STATIC_IPV4_MIN_SUBNET_OCTET = 1;
const LAN_STATIC_IPV4_MAX_SUBNET_OCTET = 254;
const LAN_STATIC_IPV4_MIN_HOST_OCTET = 2;
const LAN_STATIC_IPV4_MAX_HOST_OCTET = 254;
const GAME_STATE_HEARTBEAT_TIMEOUT_MS = 75 * 1000;
const TERMINAL_SESSION_STATES = new Set(['expired', 'stopped', 'superseded', 'kicked']);
const ROOM_MUTATION_ACTIONS = new Set(['lock', 'unlock', 'close', 'kick']);
const GAME_SESSION_STATES = new Set(['online', 'game']);

class LanStore {
  constructor(config) {
    this.config = config || {};
    this.rooms = new Map();
    this.sessions = new Map();
    this.roomSessionIds = new Map();
  }

  async startSession(rawBody, options = {}) {
    const request = parseStartSessionRequest(rawBody || {});
    request.sourceIp = normalizeSourceIp(options.sourceIp);
    const nowMs = normalizeNowMs(options.nowMs);
    const easyTier = normalizeEasyTierSettings(options.easyTier);
    ensureEasyTierSessionAvailability(easyTier);
    ensureEasyTierClientVersionSupported(
      request.clientVersion,
      this.config.easyTierMinimumOnlineLobbyCompatibleVersion
    );

    this.expireSessions(nowMs);
    if (request.createOnly && this.findRoom(request.roomId)) {
      throw httpError(409, 'LAN room already exists');
    }
    return this.startSessionInMemory(request, nowMs, easyTier);
  }

  startSessionInMemory(request, nowMs, easyTier) {
    const roomResult = this.getOrCreateRoom(request, nowMs);
    const room = roomResult.room;
    let ownerToken = roomResult.ownerToken || request.ownerToken;
    if (isRoomClosed(room)) {
      throw httpError(403, 'This room has been closed');
    }
    const priorSession = this.findLatestSessionForPlayer(room.roomId, request.playerId);
    const activePriorSession = priorSession && isSessionOnline(priorSession, nowMs)
      ? priorSession
      : null;
    if (!roomResult.created && request.playerId === room.ownerPlayerId) {
      const validOwnerToken = tokensEqual(room.ownerTokenHash, request.ownerToken);
      const validOwnerSession = activePriorSession &&
        tokensEqual(activePriorSession.sessionTokenHash, request.sessionToken);
      if (!validOwnerToken && !validOwnerSession) {
        throw httpError(403, 'Room owner credential is required');
      }
      if (!validOwnerToken && validOwnerSession) {
        ownerToken = generateAccessToken();
        room.ownerTokenHash = hashToken(ownerToken);
        room.updatedAtMs = nowMs;
      }
    }
    if (!roomResult.created && request.playerId !== room.ownerPlayerId && activePriorSession &&
      !tokensEqual(activePriorSession.sessionTokenHash, request.sessionToken)) {
      throw httpError(403, 'Existing player credential is required');
    }
    if (!room.allowNewJoins && request.playerId !== room.ownerPlayerId) {
      if (!activePriorSession ||
        !tokensEqual(activePriorSession.sessionTokenHash, request.sessionToken)) {
        throw httpError(403, 'This room is not accepting new joins');
      }
    }
    if (request.playerId !== room.ownerPlayerId && !activePriorSession) {
      if (this.countActiveSessions(room.roomId, nowMs) >= MAX_LAN_ROOM_MEMBERS) {
        throw httpError(429, 'This room is full');
      }
      if (this.countActiveSessionsForSource(room.roomId, request.sourceIp, nowMs) >=
        MAX_LAN_ROOM_MEMBERS_PER_SOURCE_IP) {
        throw httpError(429, 'Too many active members from this network');
      }
    }
    if (request.macAddress) {
      const matchingActiveSession = this.sessionsForRoom(room.roomId).find((session) =>
        session.playerId !== request.playerId &&
        session.macAddress === request.macAddress &&
        isSessionOnline(session, nowMs)
      );
      if (matchingActiveSession) {
        throw httpError(409, 'A device with this LAN MAC address is already connected');
      }
    }

    if (request.playerId === room.ownerPlayerId && request.displayName &&
      request.displayName !== room.ownerDisplayName) {
      room.ownerDisplayName = request.displayName;
      room.updatedAtMs = nowMs;
    }

    for (const session of this.sessionsForRoom(room.roomId)) {
      if (session.playerId === request.playerId && isSessionOnline(session, nowMs)) {
        session.sessionState = 'superseded';
        session.endedAtMs = nowMs;
        session.updatedAtMs = nowMs;
      }
    }

    const sessionId = generateSessionId();
    const sessionToken = generateAccessToken();
    const expiresAtMs = nowMs + (resolveEasyTierSessionTtlSeconds(this.config) * 1000);
    const relayServerDescription = buildRelayServerDescription(
      easyTier.entryNodeUrl,
      easyTier.configServerUrl
    );
    const assignedIpv4Cidr = request.macAddress
      ? this.allocateStaticIpv4Cidr(room, request.macAddress)
      : '';

    const session = {
      sessionId,
      roomId: room.roomId,
      playerId: request.playerId,
      displayName: request.displayName,
      clientVersion: request.clientVersion,
      deviceSummary: request.deviceSummary,
      mode: room.mode,
      entryNodeUrl: easyTier.entryNodeUrl,
      configServerUrl: easyTier.configServerUrl,
      aclGroup: room.aclGroup,
      networkSecret: room.networkSecret,
      sessionState: 'issued',
      sessionTokenHash: hashToken(sessionToken),
      sourceIp: request.sourceIp,
      macAddress: request.macAddress,
      usesStaticIpv4: Boolean(request.macAddress),
      assignedIpv4Cidr,
      relayServerDescription,
      gameState: 'online',
      gameStateUpdatedAtMs: 0,
      mods: request.mods,
      kickMessage: '',
      kickedAtMs: 0,
      createdAtMs: nowMs,
      updatedAtMs: nowMs,
      expiresAtMs,
      endedAtMs: 0
    };
    this.sessions.set(sessionId, session);
    this.ensureRoomSessionIds(room.roomId).add(sessionId);
    room.updatedAtMs = nowMs;
    room.lastSessionStartedAtMs = nowMs;

    return {
      sessionId,
      roomId: room.roomId,
      mode: room.mode,
      entryNodeUrl: easyTier.entryNodeUrl,
      configServerUrl: easyTier.configServerUrl,
      aclGroup: room.aclGroup,
      networkSecret: room.networkSecret,
      assignedIpv4Cidr,
      macAddress: request.macAddress,
      sessionToken,
      ownerToken: request.playerId === room.ownerPlayerId ? ownerToken : '',
      expiresAt: Math.floor(expiresAtMs / 1000)
    };
  }

  async createRoom(rawBody, options = {}) {
    parseCreateRoomRequest(rawBody || {});
    throw httpError(409, 'LAN rooms must be created by the owner starting a session');
  }

  async updateRoom(rawRoomId, rawBody, options = {}) {
    const roomId = parseRoomIdentifier(rawRoomId);
    const request = parseUpdateRoomRequest(rawBody || {});
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const room = this.findRoom(roomId);
    if (!room) {
      throw httpError(404, 'LAN room not found');
    }
    const ownerSession = request.sessionToken
      ? this.findLatestSessionForPlayer(room.roomId, room.ownerPlayerId)
      : null;
    const validOwnerSession = ownerSession &&
      isSessionOnline(ownerSession, nowMs) &&
      tokensEqual(ownerSession.sessionTokenHash, request.sessionToken);
    if (!tokensEqual(room.ownerTokenHash, request.ownerToken) && !validOwnerSession) {
      throw httpError(403, 'Only the room owner can manage this room');
    }
    if (isRoomClosed(room) && request.action !== 'close') {
      throw httpError(410, 'LAN room has been closed');
    }

    if (request.action === 'kick') {
      if (request.targetPlayerId === room.ownerPlayerId) {
        throw httpError(400, 'The room owner cannot be removed');
      }
      const targetSession = this.findLatestSessionForPlayer(roomId, request.targetPlayerId);
      if (!targetSession || !isSessionOnline(targetSession, nowMs)) {
        throw httpError(404, 'LAN room member not found');
      }
      for (const session of this.sessionsForRoom(roomId)) {
        if (session.playerId === request.targetPlayerId && isSessionOnline(session, nowMs)) {
          session.sessionState = 'kicked';
          session.kickMessage = request.kickMessage;
          session.kickedAtMs = nowMs;
          session.updatedAtMs = nowMs;
          session.endedAtMs = nowMs;
        }
      }
      room.updatedAtMs = nowMs;
      const roomInfo = await this.getRoomInfo(roomId, { nowMs });
      return {
        ...roomInfo,
        kickedPlayerId: targetSession.playerId,
        kickedDisplayName: targetSession.displayName || targetSession.playerId,
        kickMessage: request.kickMessage
      };
    }

    let allowNewJoins = room.allowNewJoins;
    let closedAtMs = room.closedAtMs;
    if (request.action === 'lock') {
      allowNewJoins = false;
      closedAtMs = 0;
    } else if (request.action === 'unlock') {
      allowNewJoins = true;
      closedAtMs = 0;
    } else if (request.action === 'close') {
      allowNewJoins = false;
      closedAtMs = nowMs;
      for (const session of this.sessionsForRoom(roomId)) {
        if (isSessionOnline(session, nowMs)) {
          session.sessionState = 'stopped';
          session.updatedAtMs = nowMs;
          session.endedAtMs = nowMs;
        }
      }
    }

    const releasedAclGroup = request.action === 'close' ? '' : room.aclGroup;
    const releasedNetworkSecret = request.action === 'close' ? '' : room.networkSecret;
    room.allowNewJoins = allowNewJoins;
    room.closedAtMs = closedAtMs;
    room.aclGroup = releasedAclGroup;
    room.networkSecret = releasedNetworkSecret;
    room.updatedAtMs = nowMs;

    if (request.action === 'close') {
      const closedRoom = {
        ...room,
        allowNewJoins,
        closedAtMs,
        aclGroup: '',
        networkSecret: '',
        updatedAtMs: nowMs
      };
      const members = this.buildRoomMembers(closedRoom, nowMs);
      this.deleteRoom(roomId);
      return {
        roomId: closedRoom.roomId,
        ownerPlayerId: closedRoom.ownerPlayerId,
        ownerDisplayName: closedRoom.ownerDisplayName,
        description: closedRoom.description,
        mode: closedRoom.mode,
        allowNewJoins: closedRoom.allowNewJoins,
        closedAtMs: closedRoom.closedAtMs,
        memberCount: members.length,
        members
      };
    }

    return this.getRoomInfo(roomId, { nowMs });
  }

  async stopSession(rawBody, options = {}) {
    const request = parseSessionCredentialRequest(rawBody);
    const sessionId = request.sessionId;
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const session = this.findSession(sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const sessionState = deriveSessionState(session, nowMs);
    const wasActive = session.endedAtMs === 0 && !TERMINAL_SESSION_STATES.has(sessionState);
    if (wasActive) {
      session.sessionState = 'stopped';
      session.updatedAtMs = nowMs;
      session.endedAtMs = nowMs;
      const room = this.findRoom(session.roomId);
      if (room) {
        room.updatedAtMs = nowMs;
      }
      this.deleteRoomsWithoutActiveOwner(nowMs);
    }

    return {
      sessionId,
      roomId: session.roomId,
      sessionState: wasActive ? 'stopped' : sessionState
    };
  }

  async reportSessionRuntime(rawBody, options = {}) {
    const request = parseSessionRuntimeRequest(rawBody || {});
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const session = this.findSession(request.sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const sessionState = deriveSessionState(session, nowMs);
    if (session.endedAtMs > 0 || TERMINAL_SESSION_STATES.has(sessionState)) {
      throw httpError(409, 'LAN session is no longer active');
    }

    const relayServerDescription =
      request.relayServerDescription || session.relayServerDescription;
    if (session.usesStaticIpv4 &&
      session.assignedIpv4Cidr !== request.assignedIpv4Cidr) {
      throw httpError(409, 'Runtime IPv4 does not match the assigned LAN address');
    }
    // Runtime reports are lease heartbeats. Status reads cannot keep an abandoned
    // client alive, so only a working EasyTier runtime can renew this session.
    const expiresAtMs = nowMs + (resolveEasyTierSessionTtlSeconds(this.config) * 1000);

    session.sessionState = 'connected';
    session.assignedIpv4Cidr = request.assignedIpv4Cidr;
    session.relayServerDescription = relayServerDescription;
    session.expiresAtMs = expiresAtMs;
    session.updatedAtMs = nowMs;
    const room = this.findRoom(session.roomId);
    if (room) {
      room.updatedAtMs = nowMs;
    }

    return this.getSessionStatus({
      sessionId: request.sessionId,
      sessionToken: request.sessionToken
    }, { nowMs });
  }

  async reportSessionGameState(rawBody, options = {}) {
    const request = parseSessionGameStateRequest(rawBody || {});
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const session = this.findSession(request.sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const sessionState = deriveSessionState(session, nowMs);
    if (session.endedAtMs > 0 || TERMINAL_SESSION_STATES.has(sessionState)) {
      throw httpError(409, 'LAN session is no longer active');
    }

    session.gameState = request.gameState;
    session.gameStateUpdatedAtMs = nowMs;
    session.updatedAtMs = nowMs;
    const room = this.findRoom(session.roomId);
    if (room) {
      room.updatedAtMs = nowMs;
    }

    const peerCount = this.countActiveSessions(session.roomId, nowMs);
    return {
      sessionId: session.sessionId,
      roomId: session.roomId,
      sessionState: deriveSessionState(session, nowMs),
      gameState: session.gameState,
      roomState: deriveRoomState(room, peerCount, this.countGameMembers(session.roomId, nowMs)),
      peerCount
    };
  }

  async reportSessionMods(rawBody, options = {}) {
    const request = parseSessionModsRequest(rawBody || {});
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const session = this.findSession(request.sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const sessionState = deriveSessionState(session, nowMs);
    if (session.endedAtMs > 0 || TERMINAL_SESSION_STATES.has(sessionState)) {
      throw httpError(409, 'LAN session is no longer active');
    }

    session.mods = request.mods;
    session.updatedAtMs = nowMs;
    const room = this.findRoom(session.roomId);
    if (room) {
      room.updatedAtMs = nowMs;
    }

    return {
      sessionId: session.sessionId,
      roomId: session.roomId,
      reportedModCount: session.mods.length
    };
  }

  async getSessionStatus(rawQuery, options = {}) {
    const request = parseSessionCredentialRequest(rawQuery);
    const sessionId = request.sessionId;
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const session = this.findSession(sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const room = this.findRoom(session.roomId);
    const peerCount = this.countActiveSessions(session.roomId, nowMs);
    const gameMemberCount = this.countGameMembers(session.roomId, nowMs);

    const response = {
      sessionId: session.sessionId,
      roomId: session.roomId,
      sessionState: deriveSessionState(session, nowMs),
      roomState: deriveRoomState(room, peerCount, gameMemberCount),
      peerCount,
      assignedIpv4Cidr: session.assignedIpv4Cidr,
      relayServerDescription: session.relayServerDescription
    };
    if (session.sessionState === 'kicked') {
      response.kickMessage = session.kickMessage;
      response.kickedAtMs = session.kickedAtMs;
    }
    return response;
  }

  async listRooms(rawQuery, options = {}) {
    const query = parseRoomListQuery(rawQuery || {});
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const orderedRooms = Array.from(this.rooms.values())
      .filter((room) => !isRoomClosed(room))
      .sort((left, right) => {
        if (left.lastSessionStartedAtMs !== right.lastSessionStartedAtMs) {
          return right.lastSessionStartedAtMs - left.lastSessionStartedAtMs;
        }
        if (left.updatedAtMs !== right.updatedAtMs) {
          return right.updatedAtMs - left.updatedAtMs;
        }
        return right.createdAtMs - left.createdAtMs;
      });
    const pageRooms = orderedRooms.slice(query.offset, query.offset + query.limit + 1);
    const hasMore = pageRooms.length > query.limit;
    const visibleRooms = hasMore ? pageRooms.slice(0, query.limit) : pageRooms;

    const rooms = visibleRooms.map((room) => {
      const members = this.buildRoomMembers(room, nowMs);
      const onlineMemberCount = members.filter((member) => member.online).length;
      const inGameMemberCount = members.filter((member) => member.gameState === 'game').length;
      return {
        roomId: room.roomId,
        ownerPlayerId: room.ownerPlayerId,
        ownerDisplayName: room.ownerDisplayName,
        description: room.description,
        mode: room.mode,
        allowNewJoins: room.allowNewJoins,
        closedAtMs: room.closedAtMs,
        memberCount: members.length,
        onlineMemberCount,
        inGameMemberCount,
        roomState: deriveRoomState(room, onlineMemberCount, inGameMemberCount),
        lastSessionStartedAtMs: room.lastSessionStartedAtMs,
        updatedAtMs: room.updatedAtMs
      };
    });

    rooms.sort((left, right) => {
      if (left.onlineMemberCount !== right.onlineMemberCount) {
        return right.onlineMemberCount - left.onlineMemberCount;
      }
      if (left.memberCount !== right.memberCount) {
        return right.memberCount - left.memberCount;
      }
      if (left.lastSessionStartedAtMs !== right.lastSessionStartedAtMs) {
        return right.lastSessionStartedAtMs - left.lastSessionStartedAtMs;
      }
      if (left.updatedAtMs !== right.updatedAtMs) {
        return right.updatedAtMs - left.updatedAtMs;
      }
      return left.roomId.localeCompare(right.roomId, 'en', { sensitivity: 'base' });
    });

    return {
      rooms,
      nextOffset: hasMore ? query.offset + visibleRooms.length : null
    };
  }

  async getRoomInfo(rawRoomId, options = {}) {
    const roomId = parseRoomIdentifier(rawRoomId);
    const nowMs = normalizeNowMs(options.nowMs);
    this.expireSessions(nowMs);

    const room = this.findRoom(roomId);
    if (!room) {
      throw httpError(404, 'LAN room not found');
    }
    if (isRoomClosed(room)) {
      throw httpError(404, 'LAN room not found');
    }

    const members = this.buildRoomMembers(room, nowMs);
    const inGameMemberCount = members.filter((member) => member.gameState === 'game').length;
    return {
      roomId: room.roomId,
      ownerPlayerId: room.ownerPlayerId,
      ownerDisplayName: room.ownerDisplayName,
      description: room.description,
      mode: room.mode,
      allowNewJoins: room.allowNewJoins,
      closedAtMs: room.closedAtMs,
      memberCount: members.length,
      inGameMemberCount,
      roomState: deriveRoomState(room, members.length, inGameMemberCount),
      members
    };
  }

  expireSessions(nowMs = Date.now()) {
    const staleBeforeMs = nowMs - (resolveEasyTierSessionTtlSeconds(this.config) * 1000);
    for (const session of this.sessions.values()) {
      if (session.endedAtMs === 0 &&
        session.expiresAtMs > 0 &&
        (session.expiresAtMs <= nowMs || session.updatedAtMs <= staleBeforeMs) &&
        !TERMINAL_SESSION_STATES.has(session.sessionState)) {
        session.sessionState = 'expired';
        session.updatedAtMs = nowMs;
        session.endedAtMs = nowMs;
        session.entryNodeUrl = '';
        session.configServerUrl = '';
        session.aclGroup = '';
        session.networkSecret = '';
      }
    }
    this.deleteRoomsWithoutActiveOwner(nowMs);
  }

  getOrCreateRoom(request, nowMs) {
    const existing = this.findRoom(request.roomId);
    if (existing) {
      return { room: existing, created: false, ownerToken: '' };
    }

    const ownerToken = generateAccessToken();
    if (this.countActiveRoomsForSource(request.sourceIp, nowMs) >= MAX_LAN_ROOMS_PER_SOURCE_IP) {
      throw httpError(429, 'Too many active LAN rooms from this network');
    }

    const inserted = this.createRoomRecord({
      roomId: request.roomId,
      playerId: request.playerId,
      displayName: request.displayName || request.playerId,
      description: request.description,
      allowNewJoins: request.allowNewJoins,
      sourceIp: request.sourceIp,
      ownerToken
    }, nowMs);

    const created = this.findRoom(request.roomId);
    if (!created) {
      throw httpError(500, 'Failed to initialize LAN room');
    }
    return {
      room: created,
      created: inserted && created.ownerPlayerId === request.playerId,
      ownerToken: inserted && created.ownerPlayerId === request.playerId ? ownerToken : ''
    };
  }

  createRoomRecord(request, nowMs) {
    if (this.rooms.has(request.roomId)) {
      return false;
    }
    const aclGroup = buildAclGroup(request.roomId);
    const networkSecret = generateNetworkSecret();
    this.rooms.set(request.roomId, {
      roomId: request.roomId,
      ownerPlayerId: request.playerId,
      ownerDisplayName: request.displayName,
      description: request.description,
      mode: 'room',
      allowNewJoins: request.allowNewJoins,
      closedAtMs: 0,
      aclGroup,
      networkSecret,
      ipv4SubnetOctet: deriveRoomIpv4SubnetOctet(request.roomId),
      ownerTokenHash: hashToken(request.ownerToken),
      ownerSourceIp: request.sourceIp,
      createdAtMs: nowMs,
      updatedAtMs: nowMs,
      lastSessionStartedAtMs: 0
    });
    this.ensureRoomSessionIds(request.roomId);
    return true;
  }

  deleteRoomsWithoutActiveOwner(nowMs = Date.now()) {
    for (const room of Array.from(this.rooms.values())) {
      const activeSessions = this.sessionsForRoom(room.roomId)
        .filter((session) => isSessionOnline(session, nowMs));
      const ownerOnline = activeSessions.some(
        (session) => session.playerId === room.ownerPlayerId
      );
      if (!ownerOnline || activeSessions.length === 0) {
        this.deleteRoom(room.roomId);
      }
    }
  }

  hasJoinedRoom(roomId, playerId) {
    return this.sessionsForRoom(roomId).some((session) => session.playerId === playerId);
  }

  findLatestSessionForPlayer(roomId, playerId) {
    let latest = null;
    for (const session of this.sessionsForRoom(roomId)) {
      if (session.playerId === playerId &&
        (!latest || session.createdAtMs >= latest.createdAtMs)) {
        latest = session;
      }
    }
    return latest;
  }

  findRoom(roomId) {
    return this.rooms.get(roomId) || null;
  }

  findSession(sessionId) {
    return this.sessions.get(sessionId) || null;
  }

  countActiveSessions(roomId, nowMs) {
    return this.sessionsForRoom(roomId)
      .filter((session) => isSessionOnline(session, nowMs))
      .length;
  }

  countActiveSessionsForSource(roomId, sourceIp, nowMs) {
    return this.sessionsForRoom(roomId)
      .filter((session) => session.sourceIp === sourceIp && isSessionOnline(session, nowMs))
      .length;
  }

  allocateStaticIpv4Cidr(room, macAddress) {
    const sessions = this.sessionsForRoom(room.roomId);
    const priorAllocation = sessions
      .filter((session) => session.macAddress === macAddress && session.assignedIpv4Cidr)
      .sort((left, right) => right.createdAtMs - left.createdAtMs)[0];
    if (priorAllocation) {
      return priorAllocation.assignedIpv4Cidr;
    }

    const usedHostOctets = new Set(
      sessions
        .filter((session) => session.macAddress !== macAddress && session.assignedIpv4Cidr)
        .map((session) => extractIpv4HostOctet(session.assignedIpv4Cidr))
        .filter((hostOctet) => hostOctet !== null)
    );
    const hostRange = LAN_STATIC_IPV4_MAX_HOST_OCTET - LAN_STATIC_IPV4_MIN_HOST_OCTET + 1;
    const firstHostOctet = LAN_STATIC_IPV4_MIN_HOST_OCTET +
      (stableIntegerFromText(macAddress) % hostRange);
    for (let offset = 0; offset < hostRange; offset += 1) {
      const hostOctet = LAN_STATIC_IPV4_MIN_HOST_OCTET +
        ((firstHostOctet - LAN_STATIC_IPV4_MIN_HOST_OCTET + offset) % hostRange);
      if (!usedHostOctets.has(hostOctet)) {
        const subnetOctet = Number(room.ipv4SubnetOctet) || deriveRoomIpv4SubnetOctet(room.roomId);
        return `10.${LAN_STATIC_IPV4_SECOND_OCTET}.${subnetOctet}.${hostOctet}/${LAN_STATIC_IPV4_PREFIX_LENGTH}`;
      }
    }
    throw httpError(429, 'No static IPv4 addresses are available in this LAN room');
  }

  countGameMembers(roomId, nowMs) {
    const latestByPlayer = new Map();
    for (const session of this.sessionsForRoom(roomId)) {
      if (!isSessionOnline(session, nowMs)) {
        continue;
      }
      const current = latestByPlayer.get(session.playerId);
      if (!current || session.createdAtMs >= current.createdAtMs) {
        latestByPlayer.set(session.playerId, session);
      }
    }
    return Array.from(latestByPlayer.values())
      .filter((session) => resolveGameSessionState(session, nowMs) === 'game')
      .length;
  }

  countActiveRoomsForSource(sourceIp, nowMs) {
    return Array.from(this.rooms.values()).filter((room) =>
      room.ownerSourceIp === sourceIp &&
      !isRoomClosed(room) &&
      this.sessionsForRoom(room.roomId).some((session) =>
        session.playerId === room.ownerPlayerId && isSessionOnline(session, nowMs)
      )
    ).length;
  }

  buildRoomMembers(room, nowMs) {
    const latestByPlayer = new Map();
    for (const session of this.sessionsForRoom(room.roomId)) {
      if (session.createdAtMs < room.createdAtMs) {
        continue;
      }
      const current = latestByPlayer.get(session.playerId);
      if (!current || session.createdAtMs >= current.createdAtMs) {
        latestByPlayer.set(session.playerId, session);
      }
    }

    const members = Array.from(latestByPlayer.values())
      .filter((session) => isSessionOnline(session, nowMs))
      .map((memberSession) => {
        const member = {
          playerId: memberSession.playerId,
          displayName: memberSession.displayName || memberSession.playerId,
          role: memberSession.playerId === room.ownerPlayerId ? 'owner' : 'member',
          online: isSessionOnline(memberSession, nowMs),
          gameState: resolveGameSessionState(memberSession, nowMs),
          assignedIpv4Cidr: memberSession.assignedIpv4Cidr
        };
        if (Array.isArray(memberSession.mods) && memberSession.mods.length > 0) {
          member.mods = memberSession.mods;
        }
        return member;
      });

    members.sort((left, right) => {
      if (left.role !== right.role) {
        return left.role === 'owner' ? -1 : 1;
      }
      if (left.online !== right.online) {
        return left.online ? -1 : 1;
      }
      return left.displayName.localeCompare(right.displayName, 'en', { sensitivity: 'base' });
    });

    return members;
  }

  ensureRoomSessionIds(roomId) {
    let sessionIds = this.roomSessionIds.get(roomId);
    if (!sessionIds) {
      sessionIds = new Set();
      this.roomSessionIds.set(roomId, sessionIds);
    }
    return sessionIds;
  }

  sessionsForRoom(roomId) {
    const sessionIds = this.roomSessionIds.get(roomId);
    if (!sessionIds) {
      return [];
    }
    return Array.from(sessionIds, (sessionId) => this.sessions.get(sessionId))
      .filter(Boolean);
  }

  deleteRoom(roomId) {
    const sessionIds = this.roomSessionIds.get(roomId);
    if (sessionIds) {
      for (const sessionId of sessionIds) {
        this.sessions.delete(sessionId);
      }
    }
    this.roomSessionIds.delete(roomId);
    this.rooms.delete(roomId);
  }
}

function parseStartSessionRequest(body) {
  const roomId = parseRoomIdentifier(firstNonEmpty(body.roomId, body.room_id));
  const playerId = normalizeRequiredIdentifier(
    firstNonEmpty(body.playerId, body.player_id),
    'playerId'
  );

  return {
    roomId,
    playerId,
    displayName: normalizeOptionalText(firstNonEmpty(body.displayName, body.display_name), MAX_ID_LENGTH),
    description: normalizeOptionalText(
      firstNonEmpty(body.description, body.roomDescription, body.room_description),
      MAX_LAN_ROOM_DESCRIPTION_LENGTH
    ),
    clientVersion: normalizeOptionalText(
      firstNonEmpty(body.clientVersion, body.client_version),
      MAX_TEXT_LENGTH
    ),
    deviceSummary: normalizeOptionalText(
      firstNonEmpty(body.deviceSummary, body.device_summary),
      MAX_TEXT_LENGTH
    ),
    macAddress: normalizeOptionalMacAddress(
      firstNonEmpty(body.macAddress, body.mac_address, body.virtualMacAddress, body.virtual_mac_address)
    ),
    mods: parseLanReportedMods(firstArray(body.mods, body.modList, body.mod_list)),
    createOnly: normalizeBoolean(
      body.createOnly !== undefined ? body.createOnly : body.create_only,
      false
    ),
    sessionToken: normalizeOptionalAccessToken(firstNonEmpty(body.sessionToken, body.session_token)),
    ownerToken: normalizeOptionalAccessToken(firstNonEmpty(body.ownerToken, body.owner_token)),
    allowNewJoins: body.allowNewJoins === undefined && body.allow_new_joins === undefined
      ? true
      : normalizeBoolean(
        body.allowNewJoins !== undefined ? body.allowNewJoins : body.allow_new_joins,
        true
      )
  };
}

function parseCreateRoomRequest(body) {
  return {
    roomId: parseRoomIdentifier(firstNonEmpty(body.roomId, body.room_id)),
    playerId: normalizeRequiredIdentifier(
      firstNonEmpty(body.playerId, body.player_id),
      'playerId'
    ),
    displayName: normalizeOptionalText(
      firstNonEmpty(body.displayName, body.display_name),
      MAX_ID_LENGTH
    ),
    allowNewJoins: normalizeBoolean(
      body.allowNewJoins !== undefined ? body.allowNewJoins : body.allow_new_joins,
      true
    )
  };
}

function parseUpdateRoomRequest(body) {
  const action = normalizeOptionalText(firstNonEmpty(body.action), MAX_ID_LENGTH).toLowerCase();
  if (!ROOM_MUTATION_ACTIONS.has(action)) {
    throw httpError(400, 'Invalid LAN room action');
  }
  const ownerToken = normalizeOptionalAccessToken(firstNonEmpty(body.ownerToken, body.owner_token));
  const sessionToken = normalizeOptionalAccessToken(firstNonEmpty(body.sessionToken, body.session_token));
  if (!ownerToken && !sessionToken) {
    throw httpError(400, 'Missing room owner credential');
  }
  const targetPlayerId = action === 'kick'
    ? normalizeRequiredIdentifier(
      firstNonEmpty(body.targetPlayerId, body.target_player_id, body.playerId, body.player_id),
      'targetPlayerId'
    )
    : '';
  return {
    action,
    ownerToken,
    sessionToken,
    targetPlayerId,
    kickMessage: action === 'kick'
      ? normalizeOptionalText(
        firstNonEmpty(body.message, body.kickMessage, body.kick_message),
        MAX_LAN_KICK_MESSAGE_LENGTH
      )
      : ''
  };
}

function parseSessionRuntimeRequest(body) {
  return {
    ...parseSessionCredentialRequest(body),
    assignedIpv4Cidr: normalizeRequiredIpv4Cidr(
      firstNonEmpty(body.assignedIpv4Cidr, body.assigned_ipv4_cidr),
      'assignedIpv4Cidr'
    ),
    relayServerDescription: normalizeOptionalText(
      firstNonEmpty(body.relayServerDescription, body.relay_server_description),
      MAX_TEXT_LENGTH
    )
  };
}

function parseSessionGameStateRequest(body) {
  const gameState = normalizeOptionalText(
    firstNonEmpty(body.gameState, body.game_state, body.state),
    MAX_ID_LENGTH
  ).toLowerCase();
  if (!GAME_SESSION_STATES.has(gameState)) {
    throw httpError(400, 'Invalid LAN game state');
  }
  return {
    ...parseSessionCredentialRequest(body),
    gameState
  };
}

function parseSessionModsRequest(body) {
  return {
    ...parseSessionCredentialRequest(body),
    mods: parseLanReportedMods(firstArray(body.mods, body.modList, body.mod_list))
  };
}

function parseSessionIdentifier(value) {
  const sessionId = normalizeRequiredIdentifier(
    typeof value === 'string'
      ? value
      : firstNonEmpty(value && value.sessionId, value && value.session_id),
    'sessionId'
  );
  return sessionId;
}

function parseSessionCredentialRequest(value) {
  const body = typeof value === 'string' ? { sessionId: value } : (value || {});
  return {
    sessionId: parseSessionIdentifier(body),
    sessionToken: normalizeRequiredAccessToken(
      firstNonEmpty(body.sessionToken, body.session_token),
      'sessionToken'
    )
  };
}

function parseRoomIdentifier(value) {
  return normalizeRequiredIdentifier(
    typeof value === 'string'
      ? value
      : firstNonEmpty(value && value.roomId, value && value.room_id),
    'roomId'
  );
}

function parseRoomListQuery(value) {
  return {
    limit: Math.max(1, Math.min(
      MAX_LAN_ROOM_LIST_LIMIT,
      parsePositiveInteger(value && value.limit, DEFAULT_LAN_ROOM_LIST_LIMIT)
    )),
    offset: Math.max(0, Math.min(
      10000,
      Number.parseInt(String(value && value.offset || '0').trim(), 10) || 0
    ))
  };
}

function normalizeRequiredIdentifier(value, fieldName) {
  const normalized = normalizeOptionalText(value, MAX_ID_LENGTH);
  if (!normalized) {
    throw httpError(400, `Missing required ${fieldName}`);
  }
  return normalized;
}

function normalizeOptionalAccessToken(value) {
  const token = String(value || '').trim();
  return /^[A-Za-z0-9_-]{32,128}$/.test(token) ? token : '';
}

function normalizeOptionalMacAddress(value) {
  const compact = String(value || '').trim().replace(/[:-]/g, '').toUpperCase();
  if (!/^[0-9A-F]{12}$/.test(compact)) {
    return '';
  }
  return compact.match(/.{2}/g).join(':');
}

function parseLanReportedMods(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  const result = [];
  const seen = new Set();
  for (const entry of value) {
    if (!entry || typeof entry !== 'object') {
      continue;
    }
    const name = normalizeOptionalText(
      firstNonEmpty(entry.name, entry.title, entry.modName, entry.mod_name),
      MAX_LAN_REPORTED_MOD_NAME_LENGTH
    );
    if (!name) {
      continue;
    }
    const workshopId = normalizeOptionalWorkshopId(
      firstNonEmpty(entry.workshopId, entry.workshop_id, entry.publishedFileId, entry.published_file_id)
    );
    const key = workshopId ? `workshop:${workshopId}` : `local:${name.toLowerCase()}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push({ name, workshopId });
    if (result.length >= MAX_LAN_REPORTED_MODS) {
      break;
    }
  }
  return result;
}

function normalizeOptionalWorkshopId(value) {
  const normalized = String(value || '').trim();
  return /^\d{1,20}$/.test(normalized) ? normalized : '';
}

function firstArray(...values) {
  return values.find(Array.isArray) || [];
}

function normalizeSourceIp(value) {
  return normalizeOptionalText(value, MAX_TEXT_LENGTH).toLowerCase() || 'unknown';
}

function normalizeRequiredAccessToken(value, fieldName) {
  const token = normalizeOptionalAccessToken(value);
  if (!token) {
    throw httpError(400, `Missing or invalid ${fieldName}`);
  }
  return token;
}

function normalizeRequiredIpv4Cidr(value, fieldName) {
  const normalized = normalizeOptionalIpv4Cidr(value);
  if (!normalized) {
    throw httpError(400, `Missing required ${fieldName}`);
  }
  return normalized;
}

function normalizeOptionalIpv4Cidr(value) {
  const normalized = normalizeOptionalText(value, MAX_TEXT_LENGTH);
  if (!normalized) {
    return '';
  }
  const segments = normalized.split('/');
  if (segments.length !== 2) {
    throw httpError(400, 'Invalid IPv4 CIDR');
  }
  const octets = segments[0].trim().split('.');
  if (octets.length !== 4) {
    throw httpError(400, 'Invalid IPv4 CIDR');
  }
  const normalizedOctets = octets.map((octet) => {
    if (!/^\d+$/.test(octet)) {
      throw httpError(400, 'Invalid IPv4 CIDR');
    }
    const parsed = Number(octet);
    if (!Number.isInteger(parsed) || parsed < 0 || parsed > 255) {
      throw httpError(400, 'Invalid IPv4 CIDR');
    }
    return String(parsed);
  });
  const prefixText = segments[1].trim();
  if (!/^\d+$/.test(prefixText)) {
    throw httpError(400, 'Invalid IPv4 CIDR');
  }
  const prefix = Number(prefixText);
  if (!Number.isInteger(prefix) || prefix < 0 || prefix > 32) {
    throw httpError(400, 'Invalid IPv4 CIDR');
  }
  return `${normalizedOctets.join('.')}/${prefix}`;
}

function extractIpv4HostOctet(value) {
  const address = String(value || '').trim().split('/')[0];
  const octets = address.split('.');
  if (octets.length !== 4) {
    return null;
  }
  const hostOctet = Number(octets[3]);
  return Number.isInteger(hostOctet) && hostOctet >= 0 && hostOctet <= 255
    ? hostOctet
    : null;
}

function deriveRoomIpv4SubnetOctet(roomId) {
  const range = LAN_STATIC_IPV4_MAX_SUBNET_OCTET - LAN_STATIC_IPV4_MIN_SUBNET_OCTET + 1;
  return LAN_STATIC_IPV4_MIN_SUBNET_OCTET + (stableIntegerFromText(roomId) % range);
}

function stableIntegerFromText(value) {
  const digest = crypto.createHash('sha256').update(String(value || ''), 'utf8').digest();
  return digest.readUInt32BE(0);
}

function normalizeOptionalText(value, maxLength = MAX_TEXT_LENGTH) {
  return String(value || '').trim().slice(0, maxLength);
}

function normalizeBoolean(value, defaultValue) {
  if (typeof value === 'boolean') {
    return value;
  }
  if (value == null || value === '') {
    return Boolean(defaultValue);
  }
  const normalized = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'y', 'on'].includes(normalized)) {
    return true;
  }
  if (['0', 'false', 'no', 'n', 'off'].includes(normalized)) {
    return false;
  }
  return Boolean(defaultValue);
}

function normalizeEasyTierSettings(settings) {
  return {
    enabled: Boolean(settings && settings.enabled),
    entryNodeUrl: normalizeOptionalText(settings && settings.entryNodeUrl, MAX_TEXT_LENGTH),
    configServerUrl: normalizeOptionalText(settings && settings.configServerUrl, MAX_TEXT_LENGTH)
  };
}

function ensureEasyTierSessionAvailability(easyTier) {
  if (!easyTier.enabled) {
    throw httpError(503, 'EasyTier cloud-control is disabled');
  }
  if (!easyTier.entryNodeUrl) {
    throw httpError(503, 'EasyTier entry node URL is unavailable');
  }
}

function ensureEasyTierClientVersionSupported(clientVersion, minimumVersion) {
  const normalizedClientVersion = normalizeOptionalText(clientVersion, MAX_TEXT_LENGTH);
  if (!normalizedClientVersion) {
    return;
  }

  const parsedClientVersion = parseComparableAppVersion(normalizedClientVersion);
  const parsedMinimumVersion = parseComparableAppVersion(minimumVersion);
  if (!parsedMinimumVersion) {
    return;
  }
  if (!parsedClientVersion || compareAppVersions(parsedClientVersion, parsedMinimumVersion) < 0) {
    throw httpError(
      426,
      `EasyTier 虚拟局域网需要客户端版本 ${minimumVersion} 或更高版本，请升级客户端后重试。 / ` +
      `EasyTier virtual LAN requires app version ${minimumVersion} or newer. Please upgrade the app and try again.`
    );
  }
}

function parseComparableAppVersion(value) {
  const matched = String(value || '').trim().match(/^v?(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?$/);
  if (!matched) {
    return null;
  }
  return matched.slice(1, 4).map((segment) => Number(segment));
}

function compareAppVersions(left, right) {
  for (let index = 0; index < left.length; index += 1) {
    if (left[index] !== right[index]) {
      return left[index] - right[index];
    }
  }
  return 0;
}

function deriveSessionState(session, nowMs) {
  if (session.endedAtMs > 0 && session.sessionState) {
    return session.sessionState;
  }
  if (session.expiresAtMs > 0 && session.expiresAtMs <= nowMs) {
    return 'expired';
  }
  return session.sessionState || 'issued';
}

function isSessionOnline(session, nowMs) {
  return session.endedAtMs === 0 &&
    session.expiresAtMs > nowMs &&
    !TERMINAL_SESSION_STATES.has(session.sessionState);
}

function resolveGameSessionState(session, nowMs) {
  return session.gameState === 'game' &&
    Number(session.gameStateUpdatedAtMs) > nowMs - GAME_STATE_HEARTBEAT_TIMEOUT_MS
    ? 'game'
    : 'online';
}

function deriveRoomState(room, peerCount, gameMemberCount = 0) {
  if (!room) {
    return 'missing';
  }
  if (isRoomClosed(room)) {
    return 'closed';
  }
  if (gameMemberCount > 0) {
    return 'in_game';
  }
  if (!room.allowNewJoins) {
    return peerCount > 0 ? 'locked' : 'closed';
  }
  return peerCount > 0 ? 'active' : 'idle';
}

function isRoomClosed(room) {
  return Boolean(room && Number(room.closedAtMs) > 0);
}

function resolveEasyTierSessionTtlSeconds(config) {
  const parsed = parsePositiveInteger(
    config && config.easyTierSessionTtlSeconds,
    DEFAULT_EASYTIER_SESSION_TTL_SECONDS
  );
  return Math.max(
    MIN_EASYTIER_SESSION_TTL_SECONDS,
    Math.min(MAX_EASYTIER_SESSION_TTL_SECONDS, parsed)
  );
}

function normalizeLanMode(value) {
  return String(value || '').trim().toLowerCase() === 'community' ? 'community' : 'room';
}

function buildAclGroup(roomId) {
  const slug = String(roomId || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  if (slug) {
    return `room-${slug}`.slice(0, MAX_TEXT_LENGTH);
  }
  const digest = crypto.createHash('sha256').update(String(roomId || '')).digest('hex');
  return `room-${digest.slice(0, 16)}`;
}

function generateSessionId() {
  return `lan_${crypto.randomUUID().replace(/-/g, '')}`;
}

function generateNetworkSecret() {
  return crypto.randomBytes(24).toString('base64url');
}

function generateAccessToken() {
  return crypto.randomBytes(32).toString('base64url');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(String(token || ''), 'utf8').digest('base64url');
}

function tokensEqual(expectedHash, token) {
  const expected = Buffer.from(String(expectedHash || ''), 'utf8');
  const actual = Buffer.from(hashToken(token), 'utf8');
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function ensureSessionAccess(session, sessionToken) {
  if (!tokensEqual(session.sessionTokenHash, sessionToken)) {
    throw httpError(403, 'Invalid LAN session credential');
  }
}

function buildRelayServerDescription(entryNodeUrl, configServerUrl) {
  const entry = normalizeOptionalText(entryNodeUrl, MAX_TEXT_LENGTH);
  const config = normalizeOptionalText(configServerUrl, MAX_TEXT_LENGTH);
  if (entry && config) {
    return `single-server relay via ${entry} (${config})`;
  }
  if (entry) {
    return `single-server relay via ${entry}`;
  }
  if (config) {
    return `single-server config via ${config}`;
  }
  return 'single-server relay';
}

function normalizeNowMs(value) {
  const normalized = Number(value);
  return Number.isFinite(normalized) && normalized > 0 ? normalized : Date.now();
}

module.exports = {
  LanStore,
  DEFAULT_EASYTIER_SESSION_TTL_SECONDS,
  resolveEasyTierSessionTtlSeconds
};
