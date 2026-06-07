'use strict';

const http = require('node:http');
const test = require('node:test');
const assert = require('node:assert/strict');

const { createApp } = require('../src/createApp');
const {
  resetPresenceStoreForTest,
  recordPresenceHeartbeat,
  buildPresenceStats
} = require('../src/presence');

function buildTestConfig(overrides = {}) {
  return {
    sharedSecret: 'test-secret',
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 600,
    presenceOfflineTimeoutSeconds: 1500,
    ...overrides
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

function listenStorage(handler) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const chunks = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', async () => {
        try {
          await handler(req, res, Buffer.concat(chunks).toString('utf8'));
        } catch (error) {
          res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({
            ok: false,
            message: error && error.message ? error.message : 'storage test failure'
          }));
        }
      });
    });
    server.listen(0, () => {
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
  assert.equal(summaryBody.heartbeatIntervalSeconds, 600);
  assert.equal(summaryBody.offlineTimeoutSeconds, 1500);
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
  assert.match(panelHtml, /fetch\(buildDataSourceUrl\(sessionsApiUrl\)/);
  assert.match(panelHtml, /fetch\(buildDataSourceUrl\(statsApiUrl/);
  assert.match(panelHtml, /一周在线趋势/);
  assert.match(panelHtml, /data-source="cf"/);
  assert.match(panelHtml, /data-source="memory"/);
  assert.match(panelHtml, /selectedDataSource/);
  assert.match(panelHtml, /source', selectedDataSource/);
  assert.match(panelHtml, /api\/presence\/assets\/echarts\.min\.js/);
  assert.match(panelHtml, /window\.echarts\.init/);
  assert.match(panelHtml, /setOption/);
  assert.doesNotMatch(panelHtml, /polyline class="chart-line"/);
  assert.ok(panelHtml.includes('https://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/presence/sessions?token=panel-secret'));
  assert.ok(panelHtml.includes('https://1315061624-boxfc2p5fb.ap-guangzhou.tencentscf.com/api/presence/stats?token=panel-secret&bucket_seconds=3600'));

  const echartsAsset = await fetch(`${baseUrl}/api/presence/assets/echarts.min.js`);
  assert.equal(echartsAsset.status, 200);
  assert.match(echartsAsset.headers.get('content-type') || '', /^application\/javascript/);
  assert.match(await echartsAsset.text(), /echarts/i);

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

  const unauthorizedStats = await fetch(`${baseUrl}/api/presence/stats`);
  assert.equal(unauthorizedStats.status, 401);

  const stats = await fetch(`${baseUrl}/api/presence/stats?token=panel-secret&bucket_seconds=3600`);
  assert.equal(stats.status, 200);
  const statsBody = await stats.json();
  assert.equal(statsBody.windowSeconds, 604800);
  assert.equal(statsBody.bucketSeconds, 3600);
  assert.equal(statsBody.currentOnline, 1);
  assert.equal(statsBody.uniqueClients, 1);
  assert.equal(statsBody.totalHeartbeats, 1);
  assert.equal(statsBody.buckets.length, 168);
});

test('presence routes use configured remote storage', async (t) => {
  resetPresenceStoreForTest();
  const storageRequests = [];
  const storage = await listenStorage(async (req, res, rawBody) => {
    const requestUrl = new URL(req.url, 'http://127.0.0.1');
    storageRequests.push({
      method: req.method,
      pathname: requestUrl.pathname,
      query: Object.fromEntries(requestUrl.searchParams.entries()),
      authorization: req.headers.authorization || '',
      body: rawBody ? JSON.parse(rawBody) : null
    });

    assert.equal(req.headers.authorization, 'Bearer storage-secret');
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });

    if (req.method === 'POST' && requestUrl.pathname === '/internal/presence/heartbeat') {
      res.end(JSON.stringify({
        ok: true,
        online: 9,
        byState: { game: 9 },
        heartbeatIntervalSeconds: 600,
        offlineTimeoutSeconds: 1500,
        checkedAt: '2026-01-01T00:00:00.000Z',
        storageBackend: 'cloudflare-d1'
      }));
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/internal/presence/summary') {
      res.end(JSON.stringify({
        ok: true,
        online: 8,
        byState: { game: 8 },
        heartbeatIntervalSeconds: Number(requestUrl.searchParams.get('heartbeat_interval_seconds')),
        offlineTimeoutSeconds: Number(requestUrl.searchParams.get('offline_timeout_seconds')),
        checkedAt: '2026-01-01T00:00:01.000Z',
        storageBackend: 'cloudflare-d1'
      }));
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/internal/presence/sessions') {
      res.end(JSON.stringify({
        ok: true,
        online: 1,
        byState: { game: 1 },
        heartbeatIntervalSeconds: 600,
        offlineTimeoutSeconds: 1500,
        checkedAt: '2026-01-01T00:00:02.000Z',
        storageBackend: 'cloudflare-d1',
        sessions: [{
          clientId: 'android:remote-device',
          deviceId: 'remote-device',
          idType: 'android_id_sha256',
          state: 'game',
          playerName: 'Remote',
          appVersion: '2.0.0',
          firstSeenAt: '2026-01-01T00:00:00.000Z',
          lastSeenAt: '2026-01-01T00:00:02.000Z',
          ageSeconds: 0,
          expiresInSeconds: 1500
        }]
      }));
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/internal/presence/stats') {
      res.end(JSON.stringify({
        ok: true,
        windowSeconds: 604800,
        bucketSeconds: Number(requestUrl.searchParams.get('bucket_seconds')),
        since: '2025-12-30T00:00:00.000Z',
        until: '2026-01-01T00:00:00.000Z',
        currentOnline: 1,
        peakOnline: 1,
        snapshotCount: 1,
        totalDevices: 1,
        buckets: []
      }));
      return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: false, message: 'not found' }));
  });
  t.after(() => storage.server.close());

  const { server, baseUrl } = await listen(createApp(buildTestConfig({
    presenceStorageUrl: storage.baseUrl,
    presenceStorageSecret: 'storage-secret'
  })));
  t.after(() => server.close());

  const heartbeat = await fetch(`${baseUrl}/api/presence/heartbeat`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      client_id: 'android:remote-device',
      device_id: 'remote-device',
      id_type: 'android_id_sha256',
      state: 'game',
      player_name: 'Remote',
      app_version: '2.0.0'
    })
  });
  assert.equal(heartbeat.status, 200);
  assert.equal((await heartbeat.json()).online, 9);
  assert.deepEqual(storageRequests.at(-1).body, {
    client_id: 'android:remote-device',
    device_id: 'remote-device',
    id_type: 'android_id_sha256',
    state: 'game',
    player_name: 'Remote',
    app_version: '2.0.0',
    heartbeat_interval_seconds: 600,
    offline_timeout_seconds: 1500
  });

  const summary = await fetch(`${baseUrl}/api/presence/summary`);
  assert.equal(summary.status, 200);
  const summaryBody = await summary.json();
  assert.equal(summaryBody.online, 8);
  assert.equal(summaryBody.storageBackend, 'cloudflare-d1');
  assert.equal(storageRequests.at(-1).query.heartbeat_interval_seconds, '600');
  assert.equal(storageRequests.at(-1).query.offline_timeout_seconds, '1500');

  const requestCountAfterRemoteSummary = storageRequests.length;
  const memorySummary = await fetch(`${baseUrl}/api/presence/summary?source=memory`);
  assert.equal(memorySummary.status, 200);
  const memorySummaryBody = await memorySummary.json();
  assert.equal(memorySummaryBody.online, 1);
  assert.equal(memorySummaryBody.storageBackend, 'memory');
  assert.equal(storageRequests.length, requestCountAfterRemoteSummary);

  const sessions = await fetch(`${baseUrl}/api/presence/sessions?token=panel-secret`);
  assert.equal(sessions.status, 200);
  const sessionsBody = await sessions.json();
  assert.equal(sessionsBody.online, 1);
  assert.equal(sessionsBody.sessions[0].playerName, 'Remote');

  const requestCountAfterRemoteSessions = storageRequests.length;
  const memorySessions = await fetch(`${baseUrl}/api/presence/sessions?token=panel-secret&source=memory`);
  assert.equal(memorySessions.status, 200);
  const memorySessionsBody = await memorySessions.json();
  assert.equal(memorySessionsBody.online, 1);
  assert.equal(memorySessionsBody.storageBackend, 'memory');
  assert.equal(memorySessionsBody.sessions[0].playerName, 'Remote');
  assert.equal(storageRequests.length, requestCountAfterRemoteSessions);

  const stats = await fetch(`${baseUrl}/api/presence/stats?token=panel-secret&bucket_seconds=3600`);
  assert.equal(stats.status, 200);
  const statsBody = await stats.json();
  assert.equal(statsBody.bucketSeconds, 3600);
  assert.equal(storageRequests.at(-1).query.bucket_seconds, '3600');

  const requestCountAfterRemoteStats = storageRequests.length;
  const memoryStats = await fetch(`${baseUrl}/api/presence/stats?token=panel-secret&source=memory&bucket_seconds=3600`);
  assert.equal(memoryStats.status, 200);
  const memoryStatsBody = await memoryStats.json();
  assert.equal(memoryStatsBody.currentOnline, 1);
  assert.equal(memoryStatsBody.bucketSeconds, 3600);
  assert.equal(storageRequests.length, requestCountAfterRemoteStats);
});

test('presence stats aggregate one week of in-memory heartbeat history', () => {
  resetPresenceStoreForTest();
  const config = buildTestConfig();
  const nowMs = Date.UTC(2026, 0, 3, 12, 0, 0);
  const hourMs = 60 * 60 * 1000;

  recordPresenceHeartbeat({
    clientId: 'android:old-device',
    deviceId: 'old-device',
    idType: 'android_id_sha256',
    state: 'game',
    playerName: 'Old',
    appVersion: '1.0.0'
  }, config, nowMs - (8 * 24 * hourMs));
  recordPresenceHeartbeat({
    clientId: 'android:first-device',
    deviceId: 'first-device',
    idType: 'android_id_sha256',
    state: 'game',
    playerName: 'First',
    appVersion: '1.0.0'
  }, config, nowMs - hourMs);
  recordPresenceHeartbeat({
    clientId: 'android:second-device',
    deviceId: 'second-device',
    idType: 'android_id_sha256',
    state: 'game',
    playerName: 'Second',
    appVersion: '1.0.0'
  }, config, nowMs - (2 * 60 * 1000));

  const stats = buildPresenceStats(config, { bucket_seconds: 3600 }, nowMs);
  assert.equal(stats.windowSeconds, 604800);
  assert.equal(stats.bucketSeconds, 3600);
  assert.equal(stats.totalHeartbeats, 2);
  assert.equal(stats.uniqueClients, 2);
  assert.equal(stats.currentOnline, 1);
  assert.equal(stats.buckets.length, 168);
  assert.equal(stats.buckets.at(-1).online, 1);
});
