'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const WebSocket = require('ws');

const { buildServer } = require('../src/server/app');
const { loadConfig } = require('../src/server/config');

test('presence service records heartbeat and returns summary/sessions/stats', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const heartbeat = await server.inject({
    method: 'POST',
    url: '/api/presence/heartbeat',
    payload: {
      client_id: 'client-a',
      device_id: 'device-a',
      id_type: 'test',
      state: 'game',
      player_name: 'Ironclad',
      app_version: '1.2.3'
    }
  });
  assert.equal(heartbeat.statusCode, 200);
  assert.equal(heartbeat.json().online, 1);
  assert.equal(heartbeat.json().storageBackend, 'sqlite3');

  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().online, 1);
  assert.equal(summary.json().totalDevices, 1);
  assert.equal(summary.json().totalOnlineUsers, 1);
  assert.deepEqual(summary.json().byState, { game: 1 });

  const unauthorized = await server.inject('/api/presence/sessions');
  assert.equal(unauthorized.statusCode, 401);

  const sessions = await server.inject('/api/presence/sessions?token=panel-secret');
  assert.equal(sessions.statusCode, 200);
  assert.equal(sessions.json().sessions.length, 1);
  assert.equal(sessions.json().sessions[0].playerName, 'Ironclad');

  const stats = await server.inject('/api/presence/stats?token=panel-secret&bucket_seconds=3600');
  assert.equal(stats.statusCode, 200);
  assert.equal(stats.json().currentOnline, 1);
  assert.equal(stats.json().totalOnlineUsers, 1);
  assert.equal(stats.json().bucketSeconds, 3600);
  assert.ok(Array.isArray(stats.json().buckets));
});

test('cloud-control exposes websocket heartbeat settings', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://presence.example.com',
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const response = await server.inject('/cloud-control.json');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), {
    heartbeat: {
      intervalSeconds: 30,
      wsUrl: 'wss://presence.example.com/api/presence/ws'
    }
  });
});

test('runtime options prefer server configuration over request overrides', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const heartbeat = await server.inject({
    method: 'POST',
    url: '/api/presence/heartbeat',
    payload: {
      client_id: 'client-override',
      heartbeat_interval_seconds: 600,
      offline_timeout_seconds: 1500
    }
  });
  assert.equal(heartbeat.statusCode, 200);
  assert.equal(heartbeat.json().heartbeatIntervalSeconds, 30);
  assert.equal(heartbeat.json().offlineTimeoutSeconds, 90);

  const summary = await server.inject(
    '/api/presence/summary?heartbeat_interval_seconds=600&offline_timeout_seconds=1500'
  );
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().heartbeatIntervalSeconds, 30);
  assert.equal(summary.json().offlineTimeoutSeconds, 90);
});

test('presence panel serves local frontend vendor assets', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const paths = [
    ['/presence', 'text/html'],
    ['/presence/app.js', 'application/javascript'],
    ['/presence/styles.css', 'text/css'],
    ['/presence/vue.global.prod.js', 'application/javascript'],
    ['/presence/vendor/vuetify.min.css', 'text/css'],
    ['/presence/vendor/vuetify.min.js', 'application/javascript'],
    ['/presence/vendor/echarts.min.js', 'application/javascript'],
    ['/presence/vendor/materialdesignicons.min.css', 'text/css'],
    ['/presence/fonts/materialdesignicons-webfont.woff2?v=7.4.47', 'font/woff2']
  ];

  for (const [url, contentType] of paths) {
    const response = await server.inject(url);
    assert.equal(response.statusCode, 200, url);
    assert.match(response.headers['content-type'], new RegExp(contentType), url);
  }
});

test('presence websocket accepts status frames', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  let ws = null;
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    if (ws) {
      ws.close();
    }
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.listen({ host: '127.0.0.1', port: 0 });
  const address = server.server.address();
  ws = new WebSocket(`ws://127.0.0.1:${address.port}/api/presence/ws`);

  await waitForSocketOpen(ws);
  ws.send(JSON.stringify({
    type: 'presence',
    client_id: 'client-ws',
    device_id: 'device-ws',
    id_type: 'test',
    state: 'game',
    player_name: 'Silent',
    app_version: '1.2.3'
  }));

  const ack = await waitForSocketMessage(ws, (message) => message.type === 'presence_ack');
  assert.equal(ack.ok, true);
  assert.equal(ack.online, 1);
  assert.equal(ack.totalOnlineUsers, 1);
  assert.equal(ack.storageBackend, 'sqlite3');

  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().online, 1);
});

function waitForSocketOpen(ws) {
  return new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
}

function waitForSocketMessage(ws, predicate) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error('Timed out waiting for websocket message'));
    }, 5000);

    function cleanup() {
      clearTimeout(timeout);
      ws.off('message', onMessage);
      ws.off('error', onError);
    }

    function onError(error) {
      cleanup();
      reject(error);
    }

    function onMessage(rawMessage) {
      const message = JSON.parse(rawMessage.toString('utf8'));
      if (predicate(message)) {
        cleanup();
        resolve(message);
      }
    }

    ws.on('message', onMessage);
    ws.once('error', onError);
  });
}
