'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { createApp } = require('../src/createApp');
const { resetPresenceStoreForTest } = require('../src/presence');

function buildTestConfig() {
  return {
    sharedSecret: 'test-secret',
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 240,
    presenceOfflineTimeoutSeconds: 500
  };
}

function listen(app) {
  return new Promise((resolve) => {
    const server = app.listen(0, () => {
      const address = server.address();
      resolve({
        server,
        baseUrl: `http://127.0.0.1:${address.port}`
      });
    });
  });
}

test('presence heartbeat upserts clients and summary returns online count', async (t) => {
  resetPresenceStoreForTest();
  const { server, baseUrl } = await listen(createApp(buildTestConfig()));
  t.after(() => server.close());

  const headers = {
    Accept: 'application/json',
    'Content-Type': 'application/json'
  };

  const first = await fetch(`${baseUrl}/api/presence/heartbeat`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      client_id: 'android:first-device',
      device_id: 'first-device',
      id_type: 'android_id_sha256',
      state: 'game'
    })
  });
  assert.equal(first.status, 200);
  assert.equal((await first.json()).online, 1);

  const duplicate = await fetch(`${baseUrl}/api/presence/heartbeat`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      client_id: 'android:first-device',
      device_id: 'first-device',
      id_type: 'android_id_sha256',
      state: 'game'
    })
  });
  assert.equal(duplicate.status, 200);
  assert.equal((await duplicate.json()).online, 1);

  const second = await fetch(`${baseUrl}/api/presence/heartbeat`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      client_id: 'install:second-client',
      device_id: 'second-client',
      id_type: 'install_id_sha256',
      state: 'game'
    })
  });
  assert.equal(second.status, 200);
  assert.equal((await second.json()).online, 2);

  const summary = await fetch(`${baseUrl}/api/presence/summary`, {
    method: 'GET',
    headers
  });
  assert.equal(summary.status, 200);
  const summaryBody = await summary.json();
  assert.equal(summaryBody.online, 2);
  assert.deepEqual(summaryBody.byState, { game: 2 });
  assert.equal(summaryBody.heartbeatIntervalSeconds, 240);
  assert.equal(summaryBody.offlineTimeoutSeconds, 500);
});

test('presence panel renders protected session details', async (t) => {
  resetPresenceStoreForTest();
  const { server, baseUrl } = await listen(createApp(buildTestConfig()));
  t.after(() => server.close());

  const heartbeat = await fetch(`${baseUrl}/api/presence/heartbeat`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      client_id: 'android:panel-device',
      device_id: 'panel-device',
      id_type: 'android_id_sha256',
      state: 'game',
      player_name: 'Watcher',
      app_version: '1.2.3',
      process: 'game',
      launch_mode: 'default'
    })
  });
  assert.equal(heartbeat.status, 200);

  const unauthorized = await fetch(`${baseUrl}/presence`);
  assert.equal(unauthorized.status, 401);
  assert.match(unauthorized.headers.get('content-type') || '', /^text\/html; charset=utf-8$/);
  assert.equal(unauthorized.headers.get('content-disposition'), 'inline');
  assert.match(await unauthorized.text(), /在线情况面板/);

  const panel = await fetch(`${baseUrl}/presence?token=panel-secret`);
  assert.equal(panel.status, 200);
  assert.match(panel.headers.get('content-type') || '', /^text\/html; charset=utf-8$/);
  assert.equal(panel.headers.get('content-disposition'), 'inline');
  const panelHtml = await panel.text();
  assert.match(panelHtml, /在线情况面板/);
  assert.match(panelHtml, /Watcher/);
  assert.match(panelHtml, /android_id_sha256/);
  assert.match(panelHtml, /1\.2\.3/);
  assert.doesNotMatch(panelHtml, /http-equiv="refresh"/);
  assert.doesNotMatch(panelHtml, />进程</);
  assert.doesNotMatch(panelHtml, />启动模式</);
  assert.match(panelHtml, /fetch\(sessionsApiUrl/);
  assert.ok(panelHtml.includes(`${baseUrl}/api/presence/sessions?token=panel-secret`));

  const sessions = await fetch(`${baseUrl}/api/presence/sessions?token=panel-secret`);
  assert.equal(sessions.status, 200);
  const sessionsBody = await sessions.json();
  assert.equal(sessionsBody.online, 1);
  assert.equal(sessionsBody.sessions.length, 1);
  assert.equal(sessionsBody.sessions[0].clientId, 'android:panel-device');
  assert.equal(sessionsBody.sessions[0].idType, 'android_id_sha256');
  assert.equal(sessionsBody.sessions[0].playerName, 'Watcher');
  assert.equal(Object.hasOwn(sessionsBody.sessions[0], 'processName'), false);
  assert.equal(Object.hasOwn(sessionsBody.sessions[0], 'launchMode'), false);
});
