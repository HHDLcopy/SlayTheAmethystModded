const DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 600;
const DEFAULT_OFFLINE_TIMEOUT_SECONDS = 1500;
const STATS_WINDOW_SECONDS = 7 * 24 * 60 * 60;
const DEFAULT_STATS_BUCKET_SECONDS = 60 * 60;
const MIN_STATS_BUCKET_SECONDS = 60 * 60;
const MAX_STATS_BUCKET_SECONDS = 60 * 60;
const HOUR_MS = 60 * 60 * 1000;
const CURRENT_SNAPSHOT_MIN_UPDATE_INTERVAL_MS = 5 * 60 * 1000;
const HEARTBEAT_WRITE_GRACE_SECONDS = 60;
const MAX_CLIENT_ID_LENGTH = 128;
const MAX_SESSIONS_RETURNED = 1000;

export default {
  async fetch(request, env) {
    try {
      return await routeRequest(request, env);
    } catch (error) {
      return jsonResponse({
        ok: false,
        error: normalizeErrorCode(error),
        message: normalizeErrorMessage(error)
      }, normalizeStatusCode(error));
    }
  },

  async scheduled(_controller, env) {
    try {
      ensureDatabaseBinding(env);
      await recordPresenceHourlySnapshot(env, resolveRuntimeOptions(null, null, env));
    } catch (error) {
      console.error(JSON.stringify({
        level: 'error',
        message: 'presence_hourly_snapshot_failed',
        error: normalizeErrorMessage(error)
      }));
      throw error;
    }
  }
};

async function routeRequest(request, env) {
  const url = new URL(request.url);
  const pathname = normalizePathname(url.pathname);

  if (request.method === 'GET' && pathname === '/healthz') {
    return jsonResponse({
      ok: true,
      service: 'sts-presence-storage',
      now: new Date().toISOString()
    });
  }

  enforceStorageSecret(request, env);
  ensureDatabaseBinding(env);

  if (request.method === 'POST' && pathname === '/internal/presence/heartbeat') {
    return jsonResponse({
      ok: true,
      ...(await handleHeartbeat(request, env, url))
    });
  }
  if (request.method === 'GET' && pathname === '/internal/presence/summary') {
    return jsonResponse({
      ok: true,
      ...(await buildPresenceSummary(env, resolveRuntimeOptions(url.searchParams, null, env)))
    });
  }
  if (request.method === 'GET' && pathname === '/internal/presence/sessions') {
    return jsonResponse({
      ok: true,
      ...(await buildPresenceSnapshot(env, resolveRuntimeOptions(url.searchParams, null, env)))
    });
  }
  if (request.method === 'GET' && pathname === '/internal/presence/stats') {
    return jsonResponse({
      ok: true,
      ...(await buildPresenceStats(env, resolveRuntimeOptions(url.searchParams, null, env), url.searchParams))
    });
  }

  throw httpError(404, 'Not found');
}

async function handleHeartbeat(request, env, url) {
  const body = await readJsonBody(request);
  const heartbeat = parseHeartbeat(body);
  const runtimeOptions = resolveRuntimeOptions(url.searchParams, body, env);
  const nowMs = Date.now();
  const writeCutoffMs = nowMs - resolveHeartbeatWriteIntervalMs(runtimeOptions);
  const result = await env.DB.prepare(`
    INSERT INTO presence_sessions (
      client_id,
      device_id,
      id_type,
      state,
      player_name,
      app_version,
      device_model,
      android_version,
      first_seen_at_ms,
      last_seen_at_ms
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(client_id) DO UPDATE SET
      device_id = excluded.device_id,
      id_type = excluded.id_type,
      state = excluded.state,
      player_name = excluded.player_name,
      app_version = excluded.app_version,
      device_model = excluded.device_model,
      android_version = excluded.android_version,
      last_seen_at_ms = excluded.last_seen_at_ms
    WHERE presence_sessions.last_seen_at_ms <= ?
  `).bind(
    heartbeat.clientId,
    heartbeat.deviceId,
    heartbeat.idType,
    heartbeat.state,
    heartbeat.playerName,
    heartbeat.appVersion,
    heartbeat.deviceModel,
    heartbeat.androidVersion,
    nowMs,
    nowMs,
    writeCutoffMs
  ).run();

  return {
    accepted: true,
    stored: Number(result && result.meta && result.meta.changes) > 0,
    heartbeatIntervalSeconds: runtimeOptions.heartbeatIntervalSeconds,
    offlineTimeoutSeconds: runtimeOptions.offlineTimeoutSeconds,
    checkedAt: new Date(nowMs).toISOString(),
    storageBackend: 'cloudflare-d1'
  };
}

async function buildPresenceSummary(env, runtimeOptions, nowMs = Date.now()) {
  const cutoffMs = nowMs - (runtimeOptions.offlineTimeoutSeconds * 1000);
  const byStateRows = await allRows(env.DB.prepare(`
    SELECT state, COUNT(*) AS count
    FROM presence_sessions
    WHERE last_seen_at_ms > ?
    GROUP BY state
  `).bind(cutoffMs));
  const byState = {};
  let online = 0;
  for (const row of byStateRows) {
    const state = String(row.state || 'unknown');
    const count = Number(row.count) || 0;
    byState[state] = count;
    online += count;
  }

  const totalDevicesRow = await env.DB.prepare('SELECT COUNT(*) AS count FROM presence_sessions').first();

  return {
    online,
    byState,
    heartbeatIntervalSeconds: runtimeOptions.heartbeatIntervalSeconds,
    offlineTimeoutSeconds: runtimeOptions.offlineTimeoutSeconds,
    checkedAt: new Date(nowMs).toISOString(),
    storageBackend: 'cloudflare-d1',
    totalDevices: Number(totalDevicesRow && totalDevicesRow.count) || 0
  };
}

function resolveHeartbeatWriteIntervalMs(runtimeOptions) {
  const intervalSeconds = Number(runtimeOptions && runtimeOptions.heartbeatIntervalSeconds) ||
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
  return Math.max(0, (intervalSeconds - HEARTBEAT_WRITE_GRACE_SECONDS) * 1000);
}

async function buildPresenceSnapshot(env, runtimeOptions, nowMs = Date.now()) {
  const cutoffMs = nowMs - (runtimeOptions.offlineTimeoutSeconds * 1000);
  const summary = await buildPresenceSummary(env, runtimeOptions, nowMs);
  const rows = await allRows(env.DB.prepare(`
    SELECT
      client_id,
      device_id,
      id_type,
      state,
      player_name,
      app_version,
      device_model,
      android_version,
      first_seen_at_ms,
      last_seen_at_ms
    FROM presence_sessions
    WHERE last_seen_at_ms > ?
    ORDER BY last_seen_at_ms DESC
    LIMIT ?
  `).bind(cutoffMs, MAX_SESSIONS_RETURNED));

  return {
    ...summary,
    sessions: rows.map((row) => serializeSession(row, runtimeOptions, nowMs))
  };
}

async function buildPresenceStats(env, runtimeOptions, query, nowMs = Date.now()) {
  const bucketSeconds = resolveStatsBucketSeconds(query);
  const bucketMs = bucketSeconds * 1000;
  const bucketCount = Math.ceil(STATS_WINDOW_SECONDS / bucketSeconds);
  const untilBucketMs = floorToBucketMs(nowMs, bucketMs);
  const sinceBucketMs = untilBucketMs - ((bucketCount - 1) * bucketMs);
  const summary = await recordPresenceHourlySnapshot(env, runtimeOptions, nowMs, {
    minUpdateIntervalMs: CURRENT_SNAPSHOT_MIN_UPDATE_INTERVAL_MS
  });
  const rows = await allRows(env.DB.prepare(`
    SELECT
      snapshot_hour_ms,
      online,
      by_state_json,
      total_devices,
      updated_at_ms
    FROM presence_hourly_snapshots
    WHERE snapshot_hour_ms >= ? AND snapshot_hour_ms <= ?
    ORDER BY snapshot_hour_ms ASC
  `).bind(sinceBucketMs, untilBucketMs));
  const rowsByBucket = new Map(rows.map((row) => [Number(row.snapshot_hour_ms) || 0, row]));
  const buckets = [];
  let peakOnline = 0;
  let snapshotCount = 0;

  for (let index = 0; index < bucketCount; index += 1) {
    const bucketStartMs = sinceBucketMs + (index * bucketMs);
    const bucket = serializeHourlySnapshotBucket(
      rowsByBucket.get(bucketStartMs),
      bucketStartMs,
      bucketMs
    );
    if (bucket.hasSnapshot) {
      snapshotCount += 1;
      peakOnline = Math.max(peakOnline, bucket.online);
    }
    buckets.push(bucket);
  }

  return {
    windowSeconds: STATS_WINDOW_SECONDS,
    bucketSeconds,
    since: new Date(sinceBucketMs).toISOString(),
    until: new Date(nowMs).toISOString(),
    currentOnline: summary.online,
    peakOnline: Math.max(peakOnline, summary.online),
    snapshotCount,
    totalDevices: summary.totalDevices,
    buckets
  };
}

async function recordPresenceHourlySnapshot(env, runtimeOptions, nowMs = Date.now(), options = {}) {
  const snapshotHourMs = floorToBucketMs(nowMs, HOUR_MS);
  const minUpdateIntervalMs = Math.max(0, Number(options.minUpdateIntervalMs) || 0);
  const updateCutoffMs = nowMs - minUpdateIntervalMs;
  const summary = await buildPresenceSummary(env, runtimeOptions, nowMs);
  await env.DB.batch([
    env.DB.prepare(`
      INSERT INTO presence_hourly_snapshots (
        snapshot_hour_ms,
        online,
        by_state_json,
        total_devices,
        created_at_ms,
        updated_at_ms
      )
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(snapshot_hour_ms) DO UPDATE SET
        online = excluded.online,
        by_state_json = excluded.by_state_json,
        total_devices = excluded.total_devices,
        updated_at_ms = excluded.updated_at_ms
      WHERE presence_hourly_snapshots.updated_at_ms <= ?
    `).bind(
      snapshotHourMs,
      summary.online,
      JSON.stringify(summary.byState || {}),
      summary.totalDevices,
      nowMs,
      nowMs,
      updateCutoffMs
    ),
    env.DB.prepare('DELETE FROM presence_hourly_snapshots WHERE snapshot_hour_ms < ?')
      .bind(snapshotHourMs - (STATS_WINDOW_SECONDS * 1000))
  ]);
  return summary;
}

function serializeHourlySnapshotBucket(row, bucketStartMs, bucketMs) {
  const hasSnapshot = Boolean(row);
  const online = hasSnapshot ? Number(row.online) || 0 : 0;
  const updatedAtMs = hasSnapshot ? Number(row.updated_at_ms) || 0 : 0;
  return {
    bucketStart: new Date(bucketStartMs).toISOString(),
    bucketEnd: new Date(bucketStartMs + bucketMs).toISOString(),
    online,
    hasSnapshot,
    byState: hasSnapshot ? safeJsonObject(row.by_state_json) : {},
    totalDevices: hasSnapshot ? Number(row.total_devices) || 0 : 0,
    recordedAt: updatedAtMs > 0 ? new Date(updatedAtMs).toISOString() : null
  };
}

function serializeSession(row, runtimeOptions, nowMs) {
  const firstSeenAtMs = Number(row.first_seen_at_ms) || 0;
  const lastSeenAtMs = Number(row.last_seen_at_ms) || 0;
  const ageSeconds = lastSeenAtMs > 0
    ? Math.max(0, Math.floor((nowMs - lastSeenAtMs) / 1000))
    : null;
  const expiresInSeconds = lastSeenAtMs > 0
    ? Math.max(0, Math.ceil(((runtimeOptions.offlineTimeoutSeconds * 1000) - (nowMs - lastSeenAtMs)) / 1000))
    : 0;

  return {
    clientId: String(row.client_id || ''),
    deviceId: String(row.device_id || ''),
    idType: String(row.id_type || ''),
    state: String(row.state || 'unknown'),
    playerName: String(row.player_name || ''),
    appVersion: String(row.app_version || ''),
    deviceModel: String(row.device_model || ''),
    androidVersion: String(row.android_version || ''),
    firstSeenAt: firstSeenAtMs > 0 ? new Date(firstSeenAtMs).toISOString() : null,
    lastSeenAt: lastSeenAtMs > 0 ? new Date(lastSeenAtMs).toISOString() : null,
    ageSeconds,
    expiresInSeconds
  };
}

function parseHeartbeat(body) {
  const clientId = normalizeClientId(firstNonEmpty(
    body.client_id,
    body.clientId,
    body.device_id,
    body.deviceId
  ));
  if (!clientId) {
    throw httpError(400, 'Missing required presence client_id or device_id');
  }

  return {
    clientId,
    deviceId: normalizeClientId(firstNonEmpty(body.device_id, body.deviceId)),
    idType: normalizeOptionalString(firstNonEmpty(body.id_type, body.idType)),
    state: normalizeOptionalString(firstNonEmpty(body.state, body.phase)) || 'game',
    playerName: normalizeOptionalString(firstNonEmpty(body.player_name, body.playerName)),
    appVersion: normalizeOptionalString(firstNonEmpty(body.app_version, body.appVersion)),
    deviceModel: normalizeOptionalString(firstNonEmpty(body.device_model, body.deviceModel)),
    androidVersion: normalizeOptionalString(firstNonEmpty(body.android_version, body.androidVersion))
  };
}

function resolveRuntimeOptions(query, body, env) {
  return {
    heartbeatIntervalSeconds: parsePositiveInteger(firstNonEmpty(
      env.PRESENCE_HEARTBEAT_INTERVAL_SECONDS,
      body && body.heartbeat_interval_seconds,
      body && body.heartbeatIntervalSeconds,
      query && query.get('heartbeat_interval_seconds'),
      query && query.get('heartbeatIntervalSeconds')
    ), DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
    offlineTimeoutSeconds: parsePositiveInteger(firstNonEmpty(
      env.PRESENCE_OFFLINE_TIMEOUT_SECONDS,
      body && body.offline_timeout_seconds,
      body && body.offlineTimeoutSeconds,
      query && query.get('offline_timeout_seconds'),
      query && query.get('offlineTimeoutSeconds')
    ), DEFAULT_OFFLINE_TIMEOUT_SECONDS)
  };
}

function resolveStatsBucketSeconds(query) {
  const parsed = parsePositiveInteger(firstNonEmpty(
    query && query.get('bucket_seconds'),
    query && query.get('bucketSeconds')
  ), DEFAULT_STATS_BUCKET_SECONDS);
  return Math.max(MIN_STATS_BUCKET_SECONDS, Math.min(MAX_STATS_BUCKET_SECONDS, parsed));
}

function floorToBucketMs(value, bucketMs) {
  return Math.floor((Number(value) || 0) / bucketMs) * bucketMs;
}

function safeJsonObject(value) {
  try {
    const parsed = JSON.parse(String(value || '{}'));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch (_error) {
    return {};
  }
}

async function allRows(statement) {
  const result = await statement.all();
  return Array.isArray(result && result.results) ? result.results : [];
}

async function readJsonBody(request) {
  try {
    const body = await request.json();
    return body && typeof body === 'object' ? body : {};
  } catch (_error) {
    throw httpError(400, 'Request body must be valid JSON');
  }
}

function enforceStorageSecret(request, env) {
  const expected = String(env.PRESENCE_STORAGE_SECRET || '').trim();
  if (!expected) {
    throw httpError(503, 'PRESENCE_STORAGE_SECRET is not configured');
  }

  const provided = parseBearerToken(request.headers.get('authorization')) ||
    String(request.headers.get('x-presence-storage-secret') || '').trim();
  if (provided !== expected) {
    throw httpError(401, 'Invalid presence storage secret');
  }
}

function parseBearerToken(value) {
  const match = /^Bearer\s+(.+)$/i.exec(String(value || '').trim());
  return match ? match[1].trim() : '';
}

function ensureDatabaseBinding(env) {
  if (!env.DB || typeof env.DB.prepare !== 'function') {
    throw httpError(503, 'D1 binding DB is not configured');
  }
}

function normalizePathname(value) {
  const pathname = String(value || '/').replace(/\/+$/g, '');
  return pathname || '/';
}

function normalizeClientId(value) {
  const normalized = normalizeOptionalString(value);
  return normalized ? normalized.slice(0, MAX_CLIENT_ID_LENGTH) : '';
}

function normalizeOptionalString(value) {
  return String(value || '').trim();
}

function firstNonEmpty() {
  for (const value of arguments) {
    const normalized = String(value || '').trim();
    if (normalized) {
      return normalized;
    }
  }
  return '';
}

function parsePositiveInteger(rawValue, fallbackValue) {
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallbackValue;
}

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store'
    }
  });
}

function httpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function normalizeStatusCode(error) {
  return error && Number.isInteger(error.statusCode) ? error.statusCode : 500;
}

function normalizeErrorCode(error) {
  const statusCode = normalizeStatusCode(error);
  return statusCode >= 500 ? 'internal_error' : 'bad_request';
}

function normalizeErrorMessage(error) {
  return error && error.message ? String(error.message) : 'Unexpected error';
}
