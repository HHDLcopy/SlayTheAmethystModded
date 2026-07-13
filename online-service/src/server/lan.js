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
const TERMINAL_SESSION_STATES = new Set(['expired', 'stopped', 'superseded']);
const ROOM_MUTATION_ACTIONS = new Set(['lock', 'unlock', 'close']);

class LanStore {
  constructor(database, config) {
    this.database = database;
    this.config = config || {};
  }

  async startSession(rawBody, options = {}) {
    const request = parseStartSessionRequest(rawBody || {});
    request.sourceIp = normalizeSourceIp(options.sourceIp);
    const nowMs = normalizeNowMs(options.nowMs);
    const easyTier = normalizeEasyTierSettings(options.easyTier);
    ensureEasyTierSessionAvailability(easyTier);

    await this.expireSessions(nowMs);

    return this.database.transaction(async (transactionDatabase) => {
      const transactionStore = new LanStore(transactionDatabase, this.config);
      return transactionStore.startSessionInTransaction(request, nowMs, easyTier);
    });
  }

  async startSessionInTransaction(request, nowMs, easyTier) {
    const roomResult = await this.getOrCreateRoom(request, nowMs);
    const room = roomResult.room;
    let ownerToken = roomResult.ownerToken || request.ownerToken;
    if (isRoomClosed(room)) {
      throw httpError(403, 'This room has been closed');
    }
    const priorSession = await this.findLatestSessionForPlayer(room.roomId, request.playerId);
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
        await this.database.run(`
          UPDATE lan_rooms
          SET owner_token_hash = ?, updated_at_ms = ?
          WHERE room_id = ?
        `, [room.ownerTokenHash, nowMs, room.roomId]);
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
      if (await this.countActiveSessions(room.roomId, nowMs) >= MAX_LAN_ROOM_MEMBERS) {
        throw httpError(429, 'This room is full');
      }
      if (await this.countActiveSessionsForSource(room.roomId, request.sourceIp, nowMs) >=
        MAX_LAN_ROOM_MEMBERS_PER_SOURCE_IP) {
        throw httpError(429, 'Too many active members from this network');
      }
    }

    if (request.playerId === room.ownerPlayerId && request.displayName &&
      request.displayName !== room.ownerDisplayName) {
      room.ownerDisplayName = request.displayName;
      await this.database.run(`
        UPDATE lan_rooms
        SET owner_display_name = ?, updated_at_ms = ?
        WHERE room_id = ?
      `, [
        room.ownerDisplayName,
        nowMs,
        room.roomId
      ]);
    }

    await this.database.run(`
      UPDATE lan_sessions
      SET
        session_state = 'superseded',
        ended_at_ms = CASE WHEN ended_at_ms > 0 THEN ended_at_ms ELSE ? END,
        updated_at_ms = ?
      WHERE room_id = ?
        AND player_id = ?
        AND ended_at_ms = 0
        AND session_state NOT IN ('expired', 'stopped', 'superseded')
    `, [
      nowMs,
      nowMs,
      room.roomId,
      request.playerId
    ]);

    const sessionId = generateSessionId();
    const sessionToken = generateAccessToken();
    const expiresAtMs = nowMs + (resolveEasyTierSessionTtlSeconds(this.config) * 1000);
    const relayServerDescription = buildRelayServerDescription(
      easyTier.entryNodeUrl,
      easyTier.configServerUrl
    );

    await this.database.run(`
      INSERT INTO lan_sessions (
        session_id,
        room_id,
        player_id,
        display_name,
        client_version,
        device_summary,
        mode,
        entry_node_url,
        config_server_url,
        acl_group,
        network_secret,
        session_state,
        session_token_hash,
        source_ip,
        assigned_ipv4_cidr,
        relay_server_description,
        created_at_ms,
        updated_at_ms,
        expires_at_ms,
        ended_at_ms
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
    `, [
      sessionId,
      room.roomId,
      request.playerId,
      request.displayName,
      request.clientVersion,
      request.deviceSummary,
      room.mode,
      easyTier.entryNodeUrl,
      easyTier.configServerUrl,
      room.aclGroup,
      room.networkSecret,
      'issued',
      hashToken(sessionToken),
      request.sourceIp,
      '',
      relayServerDescription,
      nowMs,
      nowMs,
      expiresAtMs
    ]);

    await this.database.run(`
      UPDATE lan_rooms
      SET updated_at_ms = ?, last_session_started_at_ms = ?
      WHERE room_id = ?
    `, [
      nowMs,
      nowMs,
      room.roomId
    ]);

    return {
      sessionId,
      roomId: room.roomId,
      mode: room.mode,
      entryNodeUrl: easyTier.entryNodeUrl,
      configServerUrl: easyTier.configServerUrl,
      aclGroup: room.aclGroup,
      networkSecret: room.networkSecret,
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
    await this.expireSessions(nowMs);

    const room = await this.findRoom(roomId);
    if (!room) {
      throw httpError(404, 'LAN room not found');
    }
    const ownerSession = request.sessionToken
      ? await this.findLatestSessionForPlayer(room.roomId, room.ownerPlayerId)
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
      await this.database.run(`
        UPDATE lan_sessions
        SET
          session_state = 'stopped',
          updated_at_ms = ?,
          ended_at_ms = CASE WHEN ended_at_ms > 0 THEN ended_at_ms ELSE ? END
        WHERE room_id = ?
          AND ended_at_ms = 0
          AND session_state NOT IN ('expired', 'stopped', 'superseded')
      `, [
        nowMs,
          nowMs,
          roomId
      ]);
    }

    const releasedAclGroup = request.action === 'close' ? '' : room.aclGroup;
    const releasedNetworkSecret = request.action === 'close' ? '' : room.networkSecret;
    await this.database.run(`
      UPDATE lan_rooms
      SET
        allow_new_joins = ?,
        closed_at_ms = ?,
        acl_group = ?,
        network_secret = ?,
        updated_at_ms = ?
      WHERE room_id = ?
    `, [
      allowNewJoins ? 1 : 0,
      closedAtMs,
      releasedAclGroup,
      releasedNetworkSecret,
      nowMs,
      roomId
    ]);

    if (request.action === 'close') {
      const closedRoom = {
        ...room,
        allowNewJoins,
        closedAtMs,
        aclGroup: '',
        networkSecret: '',
        updatedAtMs: nowMs
      };
      const members = await this.buildRoomMembers(closedRoom, nowMs);
      await this.deleteRoomsWithoutActiveOwner(nowMs);
      return {
        roomId: closedRoom.roomId,
        ownerPlayerId: closedRoom.ownerPlayerId,
        ownerDisplayName: closedRoom.ownerDisplayName,
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
    await this.expireSessions(nowMs);

    const session = await this.findSession(sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const sessionState = deriveSessionState(session, nowMs);
    if (session.endedAtMs === 0 && !TERMINAL_SESSION_STATES.has(sessionState)) {
      await this.database.run(`
        UPDATE lan_sessions
        SET
          session_state = 'stopped',
          updated_at_ms = ?,
          ended_at_ms = ?
        WHERE session_id = ?
      `, [
        nowMs,
        nowMs,
        sessionId
      ]);
      await this.database.run(`
        UPDATE lan_rooms
        SET updated_at_ms = ?
        WHERE room_id = ?
      `, [
        nowMs,
        session.roomId
      ]);
      await this.deleteRoomsWithoutActiveOwner(nowMs);
    }

    return {
      sessionId,
      roomId: session.roomId,
      sessionState: session.endedAtMs === 0 && !TERMINAL_SESSION_STATES.has(sessionState)
        ? 'stopped'
        : sessionState
    };
  }

  async reportSessionRuntime(rawBody, options = {}) {
    const request = parseSessionRuntimeRequest(rawBody || {});
    const nowMs = normalizeNowMs(options.nowMs);
    await this.expireSessions(nowMs);

    const session = await this.findSession(request.sessionId);
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
    // Runtime reports are lease heartbeats. Status reads cannot keep an abandoned
    // client alive, so only a working EasyTier runtime can renew this session.
    const expiresAtMs = nowMs + (resolveEasyTierSessionTtlSeconds(this.config) * 1000);

    await this.database.run(`
      UPDATE lan_sessions
      SET
        session_state = 'connected',
        assigned_ipv4_cidr = ?,
        relay_server_description = ?,
        expires_at_ms = ?,
        updated_at_ms = ?
      WHERE session_id = ?
    `, [
      request.assignedIpv4Cidr,
      relayServerDescription,
      expiresAtMs,
      nowMs,
      request.sessionId
    ]);
    await this.database.run(`
      UPDATE lan_rooms
      SET updated_at_ms = ?
      WHERE room_id = ?
    `, [
      nowMs,
      session.roomId
    ]);

    return this.getSessionStatus({
      sessionId: request.sessionId,
      sessionToken: request.sessionToken
    }, { nowMs });
  }

  async getSessionStatus(rawQuery, options = {}) {
    const request = parseSessionCredentialRequest(rawQuery);
    const sessionId = request.sessionId;
    const nowMs = normalizeNowMs(options.nowMs);
    await this.expireSessions(nowMs);

    const session = await this.findSession(sessionId);
    if (!session) {
      throw httpError(404, 'LAN session not found');
    }
    ensureSessionAccess(session, request.sessionToken);

    const room = await this.findRoom(session.roomId);
    const peerCount = await this.countActiveSessions(session.roomId, nowMs);

    return {
      sessionId: session.sessionId,
      roomId: session.roomId,
      sessionState: deriveSessionState(session, nowMs),
      roomState: deriveRoomState(room, peerCount),
      peerCount,
      assignedIpv4Cidr: session.assignedIpv4Cidr,
      relayServerDescription: session.relayServerDescription
    };
  }

  async listRooms(rawQuery, options = {}) {
    const query = parseRoomListQuery(rawQuery || {});
    const nowMs = normalizeNowMs(options.nowMs);
    await this.expireSessions(nowMs);

    const rows = await this.database.all(`
      SELECT
        room_id,
        owner_player_id,
        owner_display_name,
        mode,
        allow_new_joins,
        closed_at_ms,
        acl_group,
        network_secret,
        owner_token_hash,
        created_at_ms,
        updated_at_ms,
        last_session_started_at_ms
      FROM lan_rooms
      WHERE closed_at_ms = 0
      ORDER BY
        CASE WHEN last_session_started_at_ms > 0 THEN 0 ELSE 1 END ASC,
        last_session_started_at_ms DESC,
        updated_at_ms DESC,
        created_at_ms DESC
      LIMIT ? OFFSET ?
    `, [query.limit + 1, query.offset]);

    const hasMore = rows.length > query.limit;
    const pageRows = hasMore ? rows.slice(0, query.limit) : rows;

    const rooms = await Promise.all(pageRows.map(async (row) => {
      const room = serializeRoomRow(row);
      const members = await this.buildRoomMembers(room, nowMs);
      const onlineMemberCount = members.filter((member) => member.online).length;
      return {
        roomId: room.roomId,
        ownerPlayerId: room.ownerPlayerId,
        ownerDisplayName: room.ownerDisplayName,
        mode: room.mode,
        allowNewJoins: room.allowNewJoins,
        closedAtMs: room.closedAtMs,
        memberCount: members.length,
        onlineMemberCount,
        roomState: deriveRoomState(room, onlineMemberCount),
        lastSessionStartedAtMs: room.lastSessionStartedAtMs,
        updatedAtMs: room.updatedAtMs
      };
    }));

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
      nextOffset: hasMore ? query.offset + pageRows.length : null
    };
  }

  async getRoomInfo(rawRoomId, options = {}) {
    const roomId = parseRoomIdentifier(rawRoomId);
    const nowMs = normalizeNowMs(options.nowMs);
    await this.expireSessions(nowMs);

    const room = await this.findRoom(roomId);
    if (!room) {
      throw httpError(404, 'LAN room not found');
    }
    if (isRoomClosed(room)) {
      throw httpError(404, 'LAN room not found');
    }

    const members = await this.buildRoomMembers(room, nowMs);
    return {
      roomId: room.roomId,
      ownerPlayerId: room.ownerPlayerId,
      ownerDisplayName: room.ownerDisplayName,
      mode: room.mode,
      allowNewJoins: room.allowNewJoins,
      closedAtMs: room.closedAtMs,
      memberCount: members.length,
      members
    };
  }

  async expireSessions(nowMs = Date.now()) {
    const staleBeforeMs = nowMs - (resolveEasyTierSessionTtlSeconds(this.config) * 1000);
    await this.database.run(`
      UPDATE lan_sessions
      SET
        session_state = 'expired',
        updated_at_ms = ?,
        ended_at_ms = CASE WHEN ended_at_ms > 0 THEN ended_at_ms ELSE ? END,
        entry_node_url = '',
        config_server_url = '',
        acl_group = '',
        network_secret = ''
      WHERE ended_at_ms = 0
        AND expires_at_ms > 0
        AND (expires_at_ms <= ? OR updated_at_ms <= ?)
        AND session_state NOT IN ('expired', 'stopped', 'superseded')
    `, [
      nowMs,
      nowMs,
      nowMs,
      staleBeforeMs
    ]);
    await this.deleteRoomsWithoutActiveOwner(nowMs);
  }

  async getOrCreateRoom(request, nowMs) {
    const existing = await this.findRoom(request.roomId);
    if (existing) {
      return { room: existing, created: false, ownerToken: '' };
    }

    const ownerToken = generateAccessToken();
    if (await this.countActiveRoomsForSource(request.sourceIp, nowMs) >= MAX_LAN_ROOMS_PER_SOURCE_IP) {
      throw httpError(429, 'Too many active LAN rooms from this network');
    }

    const inserted = await this.createRoomRecord({
      roomId: request.roomId,
      playerId: request.playerId,
      displayName: request.displayName || request.playerId,
      allowNewJoins: request.allowNewJoins,
      sourceIp: request.sourceIp,
      ownerToken
    }, nowMs);

    const created = await this.findRoom(request.roomId);
    if (!created) {
      throw httpError(500, 'Failed to initialize LAN room');
    }
    return {
      room: created,
      created: inserted && created.ownerPlayerId === request.playerId,
      ownerToken: inserted && created.ownerPlayerId === request.playerId ? ownerToken : ''
    };
  }

  async createRoomRecord(request, nowMs) {
    const aclGroup = buildAclGroup(request.roomId);
    const networkSecret = generateNetworkSecret();
    const result = await this.database.run(`
      INSERT OR IGNORE INTO lan_rooms (
        room_id,
        owner_player_id,
        owner_display_name,
        mode,
        allow_new_joins,
        closed_at_ms,
        acl_group,
        network_secret,
        owner_token_hash,
        owner_source_ip,
        created_at_ms,
        updated_at_ms,
        last_session_started_at_ms
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
    `, [
      request.roomId,
      request.playerId,
      request.displayName,
      'room',
      request.allowNewJoins ? 1 : 0,
      0,
      aclGroup,
      networkSecret,
      hashToken(request.ownerToken),
      request.sourceIp,
      nowMs,
      nowMs
    ]);
    return result.changes > 0;
  }

  async deleteRoomsWithoutActiveOwner(nowMs = Date.now()) {
    await this.database.run(`
      DELETE FROM lan_rooms
      WHERE NOT EXISTS (
        SELECT 1
        FROM lan_sessions AS owner_session
        WHERE owner_session.room_id = lan_rooms.room_id
          AND owner_session.player_id = lan_rooms.owner_player_id
          AND owner_session.ended_at_ms = 0
          AND owner_session.expires_at_ms > ?
          AND owner_session.session_state NOT IN ('expired', 'stopped', 'superseded')
      )
      OR NOT EXISTS (
        SELECT 1
        FROM lan_sessions AS member_session
        WHERE member_session.room_id = lan_rooms.room_id
          AND member_session.ended_at_ms = 0
          AND member_session.expires_at_ms > ?
          AND member_session.session_state NOT IN ('expired', 'stopped', 'superseded')
      )
    `, [nowMs, nowMs]);
  }

  async hasJoinedRoom(roomId, playerId) {
    const row = await this.database.get(`
      SELECT 1 AS found
      FROM lan_sessions
      WHERE room_id = ? AND player_id = ?
      LIMIT 1
    `, [
      roomId,
      playerId
    ]);
    return Boolean(row);
  }

  async findLatestSessionForPlayer(roomId, playerId) {
    const row = await this.database.get(`
      SELECT
        session_id,
        room_id,
        player_id,
        display_name,
        client_version,
        device_summary,
        mode,
        entry_node_url,
        config_server_url,
        acl_group,
        network_secret,
        session_state,
        session_token_hash,
        assigned_ipv4_cidr,
        relay_server_description,
        created_at_ms,
        updated_at_ms,
        expires_at_ms,
        ended_at_ms
      FROM lan_sessions
      WHERE room_id = ? AND player_id = ?
      ORDER BY created_at_ms DESC
      LIMIT 1
    `, [roomId, playerId]);
    return row ? serializeSessionRow(row) : null;
  }

  async findRoom(roomId) {
    const row = await this.database.get(`
      SELECT
        room_id,
        owner_player_id,
        owner_display_name,
        mode,
        allow_new_joins,
        closed_at_ms,
        acl_group,
        network_secret,
        owner_token_hash,
        created_at_ms,
        updated_at_ms,
        last_session_started_at_ms
      FROM lan_rooms
      WHERE room_id = ?
    `, [roomId]);
    return row ? serializeRoomRow(row) : null;
  }

  async findSession(sessionId) {
    const row = await this.database.get(`
      SELECT
        session_id,
        room_id,
        player_id,
        display_name,
        client_version,
        device_summary,
        mode,
        entry_node_url,
        config_server_url,
        acl_group,
        network_secret,
        session_state,
        session_token_hash,
        assigned_ipv4_cidr,
        relay_server_description,
        created_at_ms,
        updated_at_ms,
        expires_at_ms,
        ended_at_ms
      FROM lan_sessions
      WHERE session_id = ?
    `, [sessionId]);
    return row ? serializeSessionRow(row) : null;
  }

  async countActiveSessions(roomId, nowMs) {
    const row = await this.database.get(`
      SELECT COUNT(*) AS count
      FROM lan_sessions
      WHERE room_id = ?
        AND ended_at_ms = 0
        AND expires_at_ms > ?
        AND session_state NOT IN ('expired', 'stopped', 'superseded')
    `, [
      roomId,
      nowMs
    ]);
    return Number(row && row.count) || 0;
  }

  async countActiveSessionsForSource(roomId, sourceIp, nowMs) {
    const row = await this.database.get(`
      SELECT COUNT(*) AS count
      FROM lan_sessions
      WHERE room_id = ?
        AND source_ip = ?
        AND ended_at_ms = 0
        AND expires_at_ms > ?
        AND session_state NOT IN ('expired', 'stopped', 'superseded')
    `, [roomId, sourceIp, nowMs]);
    return Number(row && row.count) || 0;
  }

  async countActiveRoomsForSource(sourceIp, nowMs) {
    const row = await this.database.get(`
      SELECT COUNT(*) AS count
      FROM lan_rooms AS rooms
      WHERE rooms.owner_source_ip = ?
        AND rooms.closed_at_ms = 0
        AND EXISTS (
          SELECT 1
          FROM lan_sessions AS owner_session
          WHERE owner_session.room_id = rooms.room_id
            AND owner_session.player_id = rooms.owner_player_id
            AND owner_session.ended_at_ms = 0
            AND owner_session.expires_at_ms > ?
            AND owner_session.session_state NOT IN ('expired', 'stopped', 'superseded')
        )
    `, [sourceIp, nowMs]);
    return Number(row && row.count) || 0;
  }

  async buildRoomMembers(room, nowMs) {
    const rows = await this.database.all(`
      SELECT
        sessions.player_id,
        sessions.display_name,
        sessions.session_state,
        sessions.assigned_ipv4_cidr,
        sessions.expires_at_ms,
        sessions.ended_at_ms,
        sessions.created_at_ms
        FROM lan_sessions AS sessions
      INNER JOIN (
        SELECT
          player_id,
          MAX(created_at_ms) AS latest_created_at_ms
        FROM lan_sessions
        WHERE room_id = ?
          AND created_at_ms >= ?
        GROUP BY player_id
      ) AS latest
        ON latest.player_id = sessions.player_id
       AND latest.latest_created_at_ms = sessions.created_at_ms
        WHERE sessions.room_id = ?
          AND sessions.ended_at_ms = 0
          AND sessions.expires_at_ms > ?
          AND sessions.session_state NOT IN ('expired', 'stopped', 'superseded')
    `, [
      room.roomId,
      room.createdAtMs,
      room.roomId,
      nowMs
    ]);

    const members = rows.map((row) => {
      const memberSession = serializeSessionRow(row);
      return {
        playerId: memberSession.playerId,
        displayName: memberSession.displayName || memberSession.playerId,
        role: memberSession.playerId === room.ownerPlayerId ? 'owner' : 'member',
        online: isSessionOnline(memberSession, nowMs),
        assignedIpv4Cidr: memberSession.assignedIpv4Cidr
      };
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
    clientVersion: normalizeOptionalText(
      firstNonEmpty(body.clientVersion, body.client_version),
      MAX_TEXT_LENGTH
    ),
    deviceSummary: normalizeOptionalText(
      firstNonEmpty(body.deviceSummary, body.device_summary),
      MAX_TEXT_LENGTH
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
  return {
    action,
    ownerToken,
    sessionToken
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

function serializeRoomRow(row) {
  return {
    roomId: normalizeOptionalText(row.room_id, MAX_ID_LENGTH),
    ownerPlayerId: normalizeOptionalText(row.owner_player_id, MAX_ID_LENGTH),
    ownerDisplayName: normalizeOptionalText(row.owner_display_name, MAX_ID_LENGTH),
    mode: normalizeLanMode(row.mode),
    allowNewJoins: Boolean(Number(row.allow_new_joins)),
    closedAtMs: Number(row.closed_at_ms) || 0,
    aclGroup: normalizeOptionalText(row.acl_group, MAX_TEXT_LENGTH),
    networkSecret: normalizeOptionalText(row.network_secret, MAX_TEXT_LENGTH),
    ownerTokenHash: normalizeOptionalText(row.owner_token_hash, MAX_TEXT_LENGTH),
    createdAtMs: Number(row.created_at_ms) || 0,
    updatedAtMs: Number(row.updated_at_ms) || 0,
    lastSessionStartedAtMs: Number(row.last_session_started_at_ms) || 0
  };
}

function serializeSessionRow(row) {
  return {
    sessionId: normalizeOptionalText(row.session_id, MAX_ID_LENGTH),
    roomId: normalizeOptionalText(row.room_id, MAX_ID_LENGTH),
    playerId: normalizeOptionalText(row.player_id, MAX_ID_LENGTH),
    displayName: normalizeOptionalText(row.display_name, MAX_ID_LENGTH),
    clientVersion: normalizeOptionalText(row.client_version, MAX_TEXT_LENGTH),
    deviceSummary: normalizeOptionalText(row.device_summary, MAX_TEXT_LENGTH),
    mode: normalizeLanMode(row.mode),
    entryNodeUrl: normalizeOptionalText(row.entry_node_url, MAX_TEXT_LENGTH),
    configServerUrl: normalizeOptionalText(row.config_server_url, MAX_TEXT_LENGTH),
    aclGroup: normalizeOptionalText(row.acl_group, MAX_TEXT_LENGTH),
    networkSecret: normalizeOptionalText(row.network_secret, MAX_TEXT_LENGTH),
    sessionState: normalizeOptionalText(row.session_state, MAX_ID_LENGTH) || 'issued',
    sessionTokenHash: normalizeOptionalText(row.session_token_hash, MAX_TEXT_LENGTH),
    assignedIpv4Cidr: normalizeOptionalText(row.assigned_ipv4_cidr, MAX_TEXT_LENGTH),
    relayServerDescription: normalizeOptionalText(row.relay_server_description, MAX_TEXT_LENGTH),
    createdAtMs: Number(row.created_at_ms) || 0,
    updatedAtMs: Number(row.updated_at_ms) || 0,
    expiresAtMs: Number(row.expires_at_ms) || 0,
    endedAtMs: Number(row.ended_at_ms) || 0
  };
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

function deriveRoomState(room, peerCount) {
  if (!room) {
    return 'missing';
  }
  if (isRoomClosed(room)) {
    return 'closed';
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
