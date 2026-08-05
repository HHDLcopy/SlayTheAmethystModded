'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { loadConfig } = require('../src/server/config');
const {
  LanStore,
  MAX_LAN_ROOM_PASSWORD_LENGTH,
  MAX_ROOM_PASSWORD_ATTEMPTS,
  hashRoomPassword,
  roomPasswordsEqual
} = require('../src/server/lan');

const EASY_TIER = {
  enabled: true,
  entryNodeUrl: 'tcp://relay.example.com:11010',
  configServerUrl: 'udp://relay.example.com:22020'
};

const NOW = 1_000_000;

function newStore() {
  return new LanStore({ easyTierSessionTtlSeconds: 90 });
}

// Throttling is opt-in, so the tests that exercise it must switch it on explicitly.
function newThrottledStore() {
  return new LanStore({
    easyTierSessionTtlSeconds: 90,
    easyTierRoomPasswordThrottleEnabled: true
  });
}

async function createRoom(store, roomId, password = '', nowMs = NOW) {
  return store.startSession(
    { roomId, playerId: 'owner', displayName: 'Owner', password },
    { nowMs, easyTier: EASY_TIER }
  );
}

async function join(store, roomId, playerId, password = '', nowMs = NOW + 1_000) {
  return store.startSession(
    { roomId, playerId, displayName: playerId, password },
    { nowMs, easyTier: EASY_TIER }
  );
}

test('room password is stored salted and never in plaintext', () => {
  const hash = hashRoomPassword('hunter2');
  assert.match(hash, /^scrypt\$[A-Za-z0-9_-]+\$[A-Za-z0-9_-]+$/);
  assert.ok(!hash.includes('hunter2'));
  assert.ok(roomPasswordsEqual(hash, 'hunter2'));
  assert.ok(!roomPasswordsEqual(hash, 'hunter3'));
});

test('the same password hashes differently per room', () => {
  // A shared salt would let one cracked room reveal every room using that password, and would
  // make identical passwords visibly identical in the room table.
  assert.notEqual(hashRoomPassword('same'), hashRoomPassword('same'));
});

test('malformed or absent password hashes fail closed', () => {
  // A corrupt record must deny entry rather than throw and take down the join path.
  assert.ok(!roomPasswordsEqual('', 'anything'));
  assert.ok(!roomPasswordsEqual(undefined, 'anything'));
  assert.ok(!roomPasswordsEqual('not-a-hash', 'anything'));
  assert.ok(!roomPasswordsEqual('scrypt$$', 'anything'));
  assert.ok(!roomPasswordsEqual('bcrypt$abc$def', 'anything'));
});

test('joining a password-protected room requires the correct password', async () => {
  const store = newStore();
  await createRoom(store, 'secret-room', 'letmein');

  await assert.rejects(
    () => join(store, 'secret-room', 'stranger', 'wrong'),
    (error) => {
      assert.equal(error.statusCode, 403);
      assert.equal(error.errorCode, 'lan_room_password_invalid');
      return true;
    }
  );

  const joined = await join(store, 'secret-room', 'friend', 'letmein');
  assert.ok(joined.sessionId);
});

test('omitting the password is reported distinctly from getting it wrong', async () => {
  const store = newStore();
  await createRoom(store, 'prompt-room', 'letmein');

  // The client uses this to decide between showing a prompt and showing "wrong password".
  await assert.rejects(
    () => join(store, 'prompt-room', 'stranger', ''),
    (error) => {
      assert.equal(error.errorCode, 'lan_room_password_required');
      return true;
    }
  );
});

test('rooms without a password are unaffected', async () => {
  const store = newStore();
  await createRoom(store, 'open-room', '');
  const joined = await join(store, 'open-room', 'anyone');
  assert.ok(joined.sessionId);
});

test('the owner never has to supply the room password', async () => {
  const store = newStore();
  const owner = await createRoom(store, 'owner-room', 'letmein');

  const rejoined = await store.startSession(
    {
      roomId: 'owner-room',
      playerId: 'owner',
      displayName: 'Owner',
      ownerToken: owner.ownerToken
    },
    { nowMs: NOW + 5_000, easyTier: EASY_TIER }
  );
  assert.ok(rejoined.sessionId);
});

test('an existing member reconnects without the password', async () => {
  const store = newStore();
  await createRoom(store, 'reconnect-room', 'letmein');
  const member = await join(store, 'reconnect-room', 'friend', 'letmein');

  // Mobile clients reconnect constantly; re-prompting on every drop would be unusable.
  const reconnected = await store.startSession(
    {
      roomId: 'reconnect-room',
      playerId: 'friend',
      displayName: 'friend',
      sessionToken: member.sessionToken
    },
    { nowMs: NOW + 10_000, easyTier: EASY_TIER }
  );
  assert.ok(reconnected.sessionId);
});

test('room projections expose hasPassword but never the hash', async () => {
  const store = newStore();
  await createRoom(store, 'listed-room', 'letmein');
  await createRoom(store, 'open-listed-room', '');

  const listing = await store.listRooms({}, { nowMs: NOW + 1_000 });
  const serializedListing = JSON.stringify(listing);
  assert.ok(!serializedListing.includes('letmein'));
  assert.ok(!serializedListing.includes('scrypt$'));
  assert.ok(!serializedListing.includes('passwordHash'));

  const locked = listing.rooms.find((room) => room.roomId === 'listed-room');
  const open = listing.rooms.find((room) => room.roomId === 'open-listed-room');
  assert.equal(locked.hasPassword, true);
  assert.equal(open.hasPassword, false);

  const info = await store.getRoomInfo('listed-room', { nowMs: NOW + 1_000 });
  assert.equal(info.hasPassword, true);
  const serializedInfo = JSON.stringify(info);
  assert.ok(!serializedInfo.includes('letmein'));
  assert.ok(!serializedInfo.includes('scrypt$'));
});

test('owner can set, change, and clear the room password', async () => {
  const store = newStore();
  const owner = await createRoom(store, 'managed-room', '');
  assert.equal(
    (await store.getRoomInfo('managed-room', { nowMs: NOW })).hasPassword,
    false
  );

  await store.updateRoom('managed-room', {
    action: 'set-password',
    ownerToken: owner.ownerToken,
    password: 'first'
  }, { nowMs: NOW + 1_000 });
  assert.equal(
    (await store.getRoomInfo('managed-room', { nowMs: NOW + 1_000 })).hasPassword,
    true
  );
  await assert.rejects(() => join(store, 'managed-room', 'a', 'wrong', NOW + 2_000));
  assert.ok(await join(store, 'managed-room', 'a', 'first', NOW + 2_000));

  await store.updateRoom('managed-room', {
    action: 'set-password',
    ownerToken: owner.ownerToken,
    password: 'second'
  }, { nowMs: NOW + 3_000 });
  await assert.rejects(() => join(store, 'managed-room', 'b', 'first', NOW + 4_000));
  assert.ok(await join(store, 'managed-room', 'b', 'second', NOW + 4_000));

  await store.updateRoom('managed-room', {
    action: 'clear-password',
    ownerToken: owner.ownerToken
  }, { nowMs: NOW + 5_000 });
  assert.equal(
    (await store.getRoomInfo('managed-room', { nowMs: NOW + 5_000 })).hasPassword,
    false
  );
  assert.ok(await join(store, 'managed-room', 'c', '', NOW + 6_000));
});

test('a non-owner cannot change the room password', async () => {
  const store = newStore();
  await createRoom(store, 'guarded-room', 'letmein');
  const member = await join(store, 'guarded-room', 'friend', 'letmein');

  await assert.rejects(
    () => store.updateRoom('guarded-room', {
      action: 'set-password',
      sessionToken: member.sessionToken,
      password: 'hijacked'
    }, { nowMs: NOW + 2_000 }),
    (error) => {
      assert.equal(error.statusCode, 403);
      return true;
    }
  );
});

test('set-password rejects an empty password', async () => {
  const store = newStore();
  const owner = await createRoom(store, 'empty-password-room', '');
  // Clearing must go through clear-password so the two actions stay unambiguous.
  await assert.rejects(
    () => store.updateRoom('empty-password-room', {
      action: 'set-password',
      ownerToken: owner.ownerToken,
      password: ''
    }, { nowMs: NOW + 1_000 }),
    (error) => {
      assert.equal(error.statusCode, 400);
      return true;
    }
  );
});

test('repeated wrong passwords throttle the room', async () => {
  const store = newThrottledStore();
  await createRoom(store, 'brute-room', 'letmein');

  for (let attempt = 0; attempt < MAX_ROOM_PASSWORD_ATTEMPTS; attempt += 1) {
    await assert.rejects(
      () => join(store, 'brute-room', `attacker-${attempt}`, 'guess', NOW + 1_000 + attempt),
      (error) => {
        assert.equal(error.errorCode, 'lan_room_password_invalid');
        return true;
      }
    );
  }

  // The budget is per-room, so rotating source IPs or player IDs does not help.
  await assert.rejects(
    () => join(store, 'brute-room', 'attacker-final', 'guess', NOW + 2_000),
    (error) => {
      assert.equal(error.statusCode, 429);
      assert.equal(error.errorCode, 'lan_room_password_throttled');
      return true;
    }
  );

  // Even the correct password is refused while throttled.
  await assert.rejects(
    () => join(store, 'brute-room', 'friend', 'letmein', NOW + 2_100),
    (error) => {
      assert.equal(error.statusCode, 429);
      return true;
    }
  );
});

test('the throttle window slides so a room recovers on its own', async () => {
  const store = newThrottledStore();
  await createRoom(store, 'recover-room', 'letmein');

  for (let attempt = 0; attempt < MAX_ROOM_PASSWORD_ATTEMPTS; attempt += 1) {
    await assert.rejects(
      () => join(store, 'recover-room', `attacker-${attempt}`, 'guess', NOW + 1_000 + attempt)
    );
  }
  await assert.rejects(() => join(store, 'recover-room', 'blocked', 'letmein', NOW + 2_000));

  // A permanent lockout would let an attacker deny entry to legitimate players forever.
  const afterWindowMs = NOW + 1_000 + (5 * 60 * 1000) + 1;
  const joined = await join(store, 'recover-room', 'friend', 'letmein', afterWindowMs);
  assert.ok(joined.sessionId);
});

test('a successful join clears the failure budget', async () => {
  const store = newThrottledStore();
  await createRoom(store, 'reset-room', 'letmein');

  for (let attempt = 0; attempt < MAX_ROOM_PASSWORD_ATTEMPTS - 1; attempt += 1) {
    await assert.rejects(
      () => join(store, 'reset-room', `guesser-${attempt}`, 'guess', NOW + 1_000 + attempt)
    );
  }
  await join(store, 'reset-room', 'friend', 'letmein', NOW + 2_000);

  // With the budget reset, a fresh run of wrong guesses is allowed before throttling again.
  await assert.rejects(
    () => join(store, 'reset-room', 'guesser-again', 'guess', NOW + 3_000),
    (error) => {
      assert.equal(error.errorCode, 'lan_room_password_invalid');
      return true;
    }
  );
});

test('password throttling stays off unless explicitly enabled', async () => {
  const store = newStore();
  await createRoom(store, 'unthrottled-room', 'letmein');

  // Deployments behind a proxy that hides the client IP would otherwise throttle the whole player
  // base, so every limiter ships disabled. Wrong guesses keep returning the plain 403.
  for (let attempt = 0; attempt < MAX_ROOM_PASSWORD_ATTEMPTS + 5; attempt += 1) {
    await assert.rejects(
      () => join(store, 'unthrottled-room', `guesser-${attempt}`, 'guess', NOW + 1_000 + attempt),
      (error) => {
        assert.equal(error.statusCode, 403);
        assert.equal(error.errorCode, 'lan_room_password_invalid');
        return true;
      }
    );
  }

  // And the correct password still works afterwards.
  assert.ok(await join(store, 'unthrottled-room', 'friend', 'letmein', NOW + 9_000));
});

test('passwords preserve whitespace and are length-bounded', async () => {
  const store = newStore();
  // Trimming would make a password containing spaces impossible to re-enter.
  await createRoom(store, 'space-room', ' pad ');
  await assert.rejects(() => join(store, 'space-room', 'a', 'pad'));
  assert.ok(await join(store, 'space-room', 'b', ' pad '));

  const overLong = 'x'.repeat(MAX_LAN_ROOM_PASSWORD_LENGTH + 20);
  await createRoom(store, 'long-room', overLong, NOW + 10_000);
  // The stored password is the truncated form, so the truncated form must authenticate.
  assert.ok(await join(
    store,
    'long-room',
    'c',
    'x'.repeat(MAX_LAN_ROOM_PASSWORD_LENGTH),
    NOW + 11_000
  ));
});

test('an outdated client is rejected before any password work', async () => {
  const store = new LanStore({
    easyTierSessionTtlSeconds: 90,
    easyTierMinimumOnlineLobbyCompatibleVersion: '1.5.5'
  });
  await assert.rejects(
    () => store.startSession(
      { roomId: 'gated-room', playerId: 'old', clientVersion: '1.5.4' },
      { nowMs: NOW, easyTier: EASY_TIER }
    ),
    (error) => {
      assert.equal(error.statusCode, 426);
      assert.equal(error.errorCode, 'lan_client_version_unsupported');
      return true;
    }
  );
  // The gate runs before room creation, so nothing was left behind.
  const listing = await store.listRooms({}, { nowMs: NOW });
  assert.deepEqual(listing.rooms, []);
});

test('every rate limiter defaults to disabled', () => {
  const defaults = loadConfig({ LOG_LEVEL: 'silent' });
  // These key on the client IP. Behind a proxy chain that collapses every request to one address
  // they would throttle the entire player base, so they must never default to on.
  assert.equal(defaults.lanRateLimitEnabled, false);
  assert.equal(defaults.easyTierRoomPasswordThrottleEnabled, false);
  assert.equal(defaults.trustProxy, false);

  const enabled = loadConfig({
    LOG_LEVEL: 'silent',
    LAN_RATE_LIMIT_ENABLED: 'true',
    EASYTIER_ROOM_PASSWORD_THROTTLE_ENABLED: 'true'
  });
  assert.equal(enabled.lanRateLimitEnabled, true);
  assert.equal(enabled.easyTierRoomPasswordThrottleEnabled, true);
});
