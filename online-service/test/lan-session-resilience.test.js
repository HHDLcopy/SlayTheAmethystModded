'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { loadConfig } = require('../src/server/config');
const {
  LanStore,
  DEFAULT_EASYTIER_OWNER_GRACE_SECONDS,
  MAX_LAN_ROOM_MEMBERS,
  resolveEasyTierOwnerGraceSeconds
} = require('../src/server/lan');
const { httpError } = require('../src/server/presence');

// app.js pulls in fastify and the native sqlite3 binding, which this suite does not need. The
// error-code normalization is small and pure, so it is reimplemented here to keep these tests
// runnable without the full dependency tree installed.
function normalizeStatusCode(error) {
  const statusCode = Number(error && error.statusCode);
  return Number.isFinite(statusCode) && statusCode >= 400 && statusCode <= 599 ? statusCode : 500;
}

function normalizeErrorCode(error) {
  if (error && error.errorCode) {
    return String(error.errorCode);
  }
  const statusCode = normalizeStatusCode(error);
  return statusCode >= 500 ? 'internal_error' : 'bad_request';
}

const EASY_TIER = {
  enabled: true,
  entryNodeUrl: 'tcp://relay.example.com:11010',
  configServerUrl: 'udp://relay.example.com:22020'
};

function newStore(overrides = {}) {
  return new LanStore({ easyTierSessionTtlSeconds: 90, ...overrides });
}

async function startOwner(store, roomId, nowMs) {
  return store.startSession(
    { roomId, playerId: 'owner', displayName: 'Owner' },
    { nowMs, easyTier: EASY_TIER }
  );
}

async function startMember(store, roomId, nowMs, playerId = 'member') {
  return store.startSession(
    { roomId, playerId, displayName: playerId },
    { nowMs, easyTier: EASY_TIER }
  );
}

// Keeps a session's lease alive the way a healthy client does, so tests can hold one member
// online while another member's lease deliberately lapses.
async function renew(store, session, nowMs) {
  return store.reportSessionRuntime({
    sessionId: session.sessionId,
    sessionToken: session.sessionToken,
    assignedIpv4Cidr: '10.126.5.184/24'
  }, { nowMs });
}

// The session TTL is 90s, so a client that only reported once would expire before any grace
// scenario begins. Walk the clock forward in sub-TTL steps the way a real 5s poll loop does.
async function renewThrough(store, session, fromMs, toMs, stepMs = 30_000) {
  for (let atMs = fromMs + stepMs; atMs < toMs; atMs += stepMs) {
    await renew(store, session, atMs);
  }
  return renew(store, session, toMs);
}

test('lan 404s carry a machine-readable reason instead of a bare status', () => {
  // The client used to read any 404 from the runtime endpoint as "this server is too old to
  // support runtime reports" and permanently stop renewing its lease. A missing session and a
  // missing endpoint must therefore be distinguishable.
  assert.equal(
    normalizeErrorCode(httpError(404, 'LAN session not found', 'lan_session_not_found')),
    'lan_session_not_found'
  );
  assert.equal(
    normalizeErrorCode(httpError(404, 'LAN room not found', 'lan_room_not_found')),
    'lan_room_not_found'
  );
  // An unlabelled error keeps the previous generic behaviour.
  assert.equal(normalizeErrorCode(httpError(400, 'Bad input')), 'bad_request');
  assert.equal(normalizeErrorCode(httpError(500, 'Boom')), 'internal_error');
});

test('lan session runtime reports a missing session with its own error code', async () => {
  const store = newStore();
  // Start a real session so the payload passes request validation, then let it expire. This is
  // exactly the shape of the production failure: a well-formed request for a session the server
  // has already swept away.
  const started = await startOwner(store, 'gone-room', 1_000_000);
  await assert.rejects(
    () => renew(store, started, 1_000_000 + 200_000),
    (error) => {
      assert.equal(error.statusCode, 404);
      assert.equal(error.errorCode, 'lan_session_not_found');
      return true;
    }
  );
});

test('room survives the owner lease lapsing while members remain', async () => {
  const store = newStore();
  const startedAtMs = 1_000_000;
  const owner = await startOwner(store, 'grace-room', startedAtMs);
  const member = await startMember(store, 'grace-room', startedAtMs);
  assert.ok(owner.sessionId);

  // The owner stops renewing; the member keeps its lease alive past the 90s session TTL.
  const afterOwnerExpiry = startedAtMs + 120_000;
  await renewThrough(store, member, startedAtMs, afterOwnerExpiry);

  const room = await store.getRoomInfo('grace-room', { nowMs: afterOwnerExpiry });
  assert.equal(room.roomId, 'grace-room');
  // Previously deleteRoomsWithoutActiveOwner removed the room here and dropped every member.
  assert.ok(room.members.length >= 1);
});

test('owner can reclaim the room inside the grace window', async () => {
  const store = newStore();
  const startedAtMs = 1_000_000;
  const owner = await startOwner(store, 'reclaim-room', startedAtMs);
  const member = await startMember(store, 'reclaim-room', startedAtMs);

  const afterOwnerExpiry = startedAtMs + 120_000;
  await renewThrough(store, member, startedAtMs, afterOwnerExpiry);

  // The owner comes back with the owner token it was issued and takes the room over again.
  const reclaimed = await store.startSession(
    {
      roomId: 'reclaim-room',
      playerId: 'owner',
      displayName: 'Owner',
      ownerToken: owner.ownerToken
    },
    { nowMs: afterOwnerExpiry + 1_000, easyTier: EASY_TIER }
  );
  assert.equal(reclaimed.roomId, 'reclaim-room');
  assert.ok(reclaimed.sessionId);
  assert.notEqual(reclaimed.sessionId, owner.sessionId);
});

test('room is deleted once the owner grace window elapses', async () => {
  const store = newStore({ easyTierOwnerGraceSeconds: 60 });
  const startedAtMs = 1_000_000;
  await startOwner(store, 'expired-grace-room', startedAtMs);
  const member = await startMember(store, 'expired-grace-room', startedAtMs);

  // The owner's 90s lease lapses, then the 60s grace window runs on top of it. The member keeps
  // renewing throughout, so any deletion here is driven purely by the owner being absent.
  await renewThrough(store, member, startedAtMs, startedAtMs + 120_000);
  assert.ok(await store.getRoomInfo('expired-grace-room', { nowMs: startedAtMs + 120_000 }));

  // Past the grace deadline the room is finally reclaimed, taking the member session with it.
  await assert.rejects(
    () => renew(store, member, startedAtMs + 150_000),
    (error) => {
      assert.equal(error.statusCode, 404);
      assert.equal(error.errorCode, 'lan_session_not_found');
      return true;
    }
  );
});

test('grace window resets after the owner returns', async () => {
  const store = newStore({ easyTierOwnerGraceSeconds: 60 });
  const startedAtMs = 1_000_000;
  const owner = await startOwner(store, 'reset-grace-room', startedAtMs);
  const member = await startMember(store, 'reset-grace-room', startedAtMs);

  const graceOpenedAtMs = startedAtMs + 120_000;
  await renewThrough(store, member, startedAtMs, graceOpenedAtMs);

  // Owner reclaims inside the window, which must clear the pending deadline.
  const reclaimedAtMs = graceOpenedAtMs + 20_000;
  const reclaimed = await store.startSession(
    {
      roomId: 'reset-grace-room',
      playerId: 'owner',
      displayName: 'Owner',
      ownerToken: owner.ownerToken
    },
    { nowMs: reclaimedAtMs, easyTier: EASY_TIER }
  );

  // Had the stale deadline survived the reclaim, this sweep would sit past the original window
  // and delete a room whose owner is demonstrably online.
  const laterMs = reclaimedAtMs + 50_000;
  await renew(store, reclaimed, laterMs);
  await renewThrough(store, member, graceOpenedAtMs, laterMs);
  const room = await store.getRoomInfo('reset-grace-room', { nowMs: laterMs });
  assert.equal(room.roomId, 'reset-grace-room');
});

test('empty rooms are still removed immediately regardless of grace', async () => {
  const store = newStore();
  const startedAtMs = 1_000_000;
  const owner = await startOwner(store, 'empty-room', startedAtMs);
  await store.stopSession(
    { sessionId: owner.sessionId, sessionToken: owner.sessionToken },
    { nowMs: startedAtMs + 1_000 }
  );

  // Nobody is left to wait for, so holding the room would only leak it.
  await assert.rejects(
    () => store.getRoomInfo('empty-room', { nowMs: startedAtMs + 2_000 }),
    (error) => {
      assert.equal(error.statusCode, 404);
      assert.equal(error.errorCode, 'lan_room_not_found');
      return true;
    }
  );
});

test('owner grace window is configurable and clamped', () => {
  assert.equal(resolveEasyTierOwnerGraceSeconds({}), DEFAULT_EASYTIER_OWNER_GRACE_SECONDS);
  assert.equal(resolveEasyTierOwnerGraceSeconds({ easyTierOwnerGraceSeconds: 300 }), 300);
  // Zero is meaningful: it restores the old delete-immediately behaviour.
  assert.equal(resolveEasyTierOwnerGraceSeconds({ easyTierOwnerGraceSeconds: 0 }), 0);
  // Out-of-range values clamp rather than disabling the sweep entirely.
  assert.equal(resolveEasyTierOwnerGraceSeconds({ easyTierOwnerGraceSeconds: 999_999 }), 3600);
  assert.equal(
    resolveEasyTierOwnerGraceSeconds({ easyTierOwnerGraceSeconds: -5 }),
    DEFAULT_EASYTIER_OWNER_GRACE_SECONDS
  );
});

test('owner grace window is read from the environment', () => {
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent' }).easyTierOwnerGraceSeconds,
    DEFAULT_EASYTIER_OWNER_GRACE_SECONDS
  );
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', EASYTIER_OWNER_GRACE_SECONDS: '240' })
      .easyTierOwnerGraceSeconds,
    240
  );
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', EASYTIER_OWNER_GRACE_SECONDS: '0' })
      .easyTierOwnerGraceSeconds,
    0
  );
});

test('a room holds 64 members even when they share one source address', async () => {
  const store = newStore();
  const startedAtMs = 1_000_000;
  await startOwner(store, 'capacity-room', startedAtMs);

  // Every player arrives from the same address here, which is what the production proxy chain
  // looks like: the per-source cap must not become the effective room limit.
  const sharedIp = '203.0.113.9';
  for (let index = 0; index < MAX_LAN_ROOM_MEMBERS - 1; index += 1) {
    await store.startSession(
      { roomId: 'capacity-room', playerId: `member-${index}`, displayName: `m${index}` },
      { nowMs: startedAtMs + 1_000 + index, sourceIp: sharedIp, easyTier: EASY_TIER }
    );
  }

  const room = await store.getRoomInfo('capacity-room', { nowMs: startedAtMs + 2_000 });
  assert.equal(room.memberCount, MAX_LAN_ROOM_MEMBERS);

  // One past the cap is refused.
  await assert.rejects(
    () => store.startSession(
      { roomId: 'capacity-room', playerId: 'member-overflow', displayName: 'overflow' },
      { nowMs: startedAtMs + 3_000, sourceIp: sharedIp, easyTier: EASY_TIER }
    ),
    (error) => {
      assert.equal(error.statusCode, 429);
      return true;
    }
  );
});
