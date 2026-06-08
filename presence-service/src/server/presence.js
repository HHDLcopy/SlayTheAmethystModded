'use strict';

const {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS,
  firstNonEmpty,
  parsePositiveInteger
} = require('./config');

const STATS_WINDOW_SECONDS = 7 * 24 * 60 * 60;
const DEFAULT_STATS_BUCKET_SECONDS = 60 * 60;
const MIN_STATS_BUCKET_SECONDS = 60 * 60;
const MAX_STATS_BUCKET_SECONDS = 60 * 60;
const HOUR_MS = 60 * 60 * 1000;
const CURRENT_SNAPSHOT_MIN_UPDATE_INTERVAL_MS = 5 * 60 * 1000;
const HEARTBEAT_WRITE_GRACE_SECONDS = 60;
const MAX_CLIENT_ID_LENGTH = 128;

class PresenceStore {
  constructor(database, config) {
    this.database = database;
    this.config = config || {};
  }

  runtimeOptions(query, body) {
    return resolveRuntimeOptions(this.config, query, body);
  }

  async recordHeartbeat(rawBody, nowMs = Date.now()) {
    const heartbeat = parseHeartbeat(rawBody || {});
    const runtimeOptions = this.runtimeOptions(null, rawBody);
    const writeCutoffMs = nowMs - resolveHeartbeatWriteIntervalMs(runtimeOptions);
    const result = await this.database.run(`
      INSERT INTO presence_sessions (
        client_id,
        device_id,
        id_type,
        state,
        player_name,
        app_version,
        first_seen_at_ms,
        last_seen_at_ms
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(client_id) DO UPDATE SET
        device_id = excluded.device_id,
        id_type = excluded.id_type,
        state = excluded.state,
        player_name = excluded.player_name,
        app_version = excluded.app_version,
        last_seen_at_ms = excluded.last_seen_at_ms
      WHERE presence_sessions.last_seen_at_ms <= ?
    `, [
      heartbeat.clientId,
      heartbeat.deviceId,
      heartbeat.idType,
      heartbeat.state,
      heartbeat.playerName,
      heartbeat.appVersion,
      nowMs,
      nowMs,
      writeCutoffMs
    ]);
    const summary = await this.buildSummary(null, nowMs);

    return {
      accepted: true,
      stored: Number(result.changes) > 0,
      ...summary
    };
  }

  async buildSummary(query, nowMs = Date.now()) {
    const runtimeOptions = this.runtimeOptions(query, null);
    const cutoffMs = nowMs - (runtimeOptions.offlineTimeoutSeconds * 1000);
    const byStateRows = await this.database.all(`
      SELECT state, COUNT(*) AS count
      FROM presence_sessions
      WHERE last_seen_at_ms > ?
      GROUP BY state
    `, [cutoffMs]);
    const byState = {};
    let online = 0;
    for (const row of byStateRows) {
      const state = String(row.state || 'unknown');
      const count = Number(row.count) || 0;
      byState[state] = count;
      online += count;
    }

    const totalDevicesRow = await this.database.get('SELECT COUNT(*) AS count FROM presence_sessions');
    const totalOnlineUsers = Number(totalDevicesRow && totalDevicesRow.count) || 0;

    return {
      online,
      byState,
      heartbeatIntervalSeconds: runtimeOptions.heartbeatIntervalSeconds,
      offlineTimeoutSeconds: runtimeOptions.offlineTimeoutSeconds,
      checkedAt: new Date(nowMs).toISOString(),
      storageBackend: 'sqlite3',
      totalDevices: totalOnlineUsers,
      totalOnlineUsers
    };
  }

  async buildSnapshot(query, nowMs = Date.now()) {
    const runtimeOptions = this.runtimeOptions(query, null);
    const cutoffMs = nowMs - (runtimeOptions.offlineTimeoutSeconds * 1000);
    const summary = await this.buildSummary(query, nowMs);
    const rows = await this.database.all(`
      SELECT
        client_id,
        device_id,
        id_type,
        state,
        player_name,
        app_version,
        first_seen_at_ms,
        last_seen_at_ms
      FROM presence_sessions
      WHERE last_seen_at_ms > ?
      ORDER BY last_seen_at_ms DESC
      LIMIT ?
    `, [
      cutoffMs,
      Number(this.config.maxSessionsReturned) || 1000
    ]);

    return {
      ...summary,
      sessions: rows.map((row) => serializeSession(row, runtimeOptions, nowMs))
    };
  }

  async buildStats(query, nowMs = Date.now()) {
    const runtimeOptions = this.runtimeOptions(query, null);
    const bucketSeconds = resolveStatsBucketSeconds(query);
    const bucketMs = bucketSeconds * 1000;
    const bucketCount = Math.ceil(STATS_WINDOW_SECONDS / bucketSeconds);
    const untilBucketMs = floorToBucketMs(nowMs, bucketMs);
    const sinceBucketMs = untilBucketMs - ((bucketCount - 1) * bucketMs);
    const summary = await this.recordHourlySnapshot(nowMs, {
      runtimeOptions,
      minUpdateIntervalMs: CURRENT_SNAPSHOT_MIN_UPDATE_INTERVAL_MS
    });
    const rows = await this.database.all(`
      SELECT
        snapshot_hour_ms,
        online,
        by_state_json,
        total_devices,
        updated_at_ms
      FROM presence_hourly_snapshots
      WHERE snapshot_hour_ms >= ? AND snapshot_hour_ms <= ?
      ORDER BY snapshot_hour_ms ASC
    `, [sinceBucketMs, untilBucketMs]);
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
      totalOnlineUsers: summary.totalOnlineUsers,
      buckets
    };
  }

  async recordHourlySnapshot(nowMs = Date.now(), options = {}) {
    const runtimeOptions = options.runtimeOptions || this.runtimeOptions(null, null);
    const snapshotHourMs = floorToBucketMs(nowMs, HOUR_MS);
    const minUpdateIntervalMs = Math.max(0, Number(options.minUpdateIntervalMs) || 0);
    const updateCutoffMs = nowMs - minUpdateIntervalMs;
    const summary = await this.buildSummary(null, nowMs);
    await this.database.run(`
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
    `, [
      snapshotHourMs,
      summary.online,
      JSON.stringify(summary.byState || {}),
      summary.totalDevices,
      nowMs,
      nowMs,
      updateCutoffMs
    ]);
    await this.database.run(
      'DELETE FROM presence_hourly_snapshots WHERE snapshot_hour_ms < ?',
      [snapshotHourMs - (STATS_WINDOW_SECONDS * 1000)]
    );

    return {
      ...summary,
      heartbeatIntervalSeconds: runtimeOptions.heartbeatIntervalSeconds,
      offlineTimeoutSeconds: runtimeOptions.offlineTimeoutSeconds
    };
  }
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
    appVersion: normalizeOptionalString(firstNonEmpty(body.app_version, body.appVersion))
  };
}

function resolveRuntimeOptions(config, query, body) {
  return {
    heartbeatIntervalSeconds: parsePositiveInteger(firstNonEmpty(
      config && config.presenceHeartbeatIntervalSeconds,
      body && body.heartbeat_interval_seconds,
      body && body.heartbeatIntervalSeconds,
      query && (query.heartbeat_interval_seconds || query.heartbeatIntervalSeconds)
    ), DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
    offlineTimeoutSeconds: parsePositiveInteger(firstNonEmpty(
      config && config.presenceOfflineTimeoutSeconds,
      body && body.offline_timeout_seconds,
      body && body.offlineTimeoutSeconds,
      query && (query.offline_timeout_seconds || query.offlineTimeoutSeconds)
    ), DEFAULT_OFFLINE_TIMEOUT_SECONDS)
  };
}

function resolveStatsBucketSeconds(query) {
  const parsed = parsePositiveInteger(firstNonEmpty(
    query && (query.bucket_seconds || query.bucketSeconds)
  ), DEFAULT_STATS_BUCKET_SECONDS);
  return Math.max(MIN_STATS_BUCKET_SECONDS, Math.min(MAX_STATS_BUCKET_SECONDS, parsed));
}

function resolveHeartbeatWriteIntervalMs(runtimeOptions) {
  const intervalSeconds = Number(runtimeOptions && runtimeOptions.heartbeatIntervalSeconds) ||
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
  return Math.max(0, (intervalSeconds - HEARTBEAT_WRITE_GRACE_SECONDS) * 1000);
}

function serializeHourlySnapshotBucket(row, bucketStartMs, bucketMs) {
  const hasSnapshot = Boolean(row);
  const online = hasSnapshot ? Number(row.online) || 0 : 0;
  const updatedAtMs = hasSnapshot ? Number(row.updated_at_ms) || 0 : 0;
  const totalDevices = hasSnapshot ? Number(row.total_devices) || 0 : 0;
  return {
    bucketStart: new Date(bucketStartMs).toISOString(),
    bucketEnd: new Date(bucketStartMs + bucketMs).toISOString(),
    online,
    hasSnapshot,
    byState: hasSnapshot ? safeJsonObject(row.by_state_json) : {},
    totalDevices,
    totalOnlineUsers: totalDevices,
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
    firstSeenAt: firstSeenAtMs > 0 ? new Date(firstSeenAtMs).toISOString() : null,
    lastSeenAt: lastSeenAtMs > 0 ? new Date(lastSeenAtMs).toISOString() : null,
    ageSeconds,
    expiresInSeconds
  };
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

function normalizeClientId(value) {
  const normalized = normalizeOptionalString(value);
  return normalized ? normalized.slice(0, MAX_CLIENT_ID_LENGTH) : '';
}

function normalizeOptionalString(value) {
  return String(value || '').trim();
}

function httpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

module.exports = {
  PresenceStore,
  STATS_WINDOW_SECONDS,
  HOUR_MS,
  httpError,
  parseHeartbeat,
  resolveRuntimeOptions,
  resolveStatsBucketSeconds,
  serializeSession
};
