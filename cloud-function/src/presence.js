'use strict';

const { firstNonEmpty, httpError } = require('./utils');

const DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 600;
const DEFAULT_OFFLINE_TIMEOUT_SECONDS = 1500;
const STATS_WINDOW_SECONDS = 7 * 24 * 60 * 60;
const DEFAULT_STATS_BUCKET_SECONDS = 60 * 60;
const MIN_STATS_BUCKET_SECONDS = 60 * 60;
const MAX_STATS_BUCKET_SECONDS = 60 * 60;
const MAX_CLIENT_ID_LENGTH = 128;
const presenceSessions = new Map();
const presenceHeartbeatHistory = [];

function parsePresenceHeartbeatRequest(req) {
  const body = req.body && typeof req.body === 'object' ? req.body : {};
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

function recordPresenceHeartbeat(heartbeat, currentConfig, nowMs = Date.now()) {
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs);

  const existing = presenceSessions.get(heartbeat.clientId);
  const session = {
    clientId: heartbeat.clientId,
    deviceId: heartbeat.deviceId,
    idType: heartbeat.idType,
    state: heartbeat.state,
    playerName: heartbeat.playerName,
    appVersion: heartbeat.appVersion,
    firstSeenAtMs: existing ? existing.firstSeenAtMs : nowMs,
    lastSeenAtMs: nowMs
  };
  presenceSessions.set(heartbeat.clientId, session);
  recordPresenceHeartbeatHistory(heartbeat, nowMs, currentConfig);

  return buildPresenceSummary(currentConfig, nowMs);
}

function buildPresenceSummary(currentConfig, nowMs = Date.now()) {
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs);

  const byState = {};
  for (const session of presenceSessions.values()) {
    const state = session.state || 'unknown';
    byState[state] = (byState[state] || 0) + 1;
  }

  return {
    online: presenceSessions.size,
    byState,
    heartbeatIntervalSeconds: resolveHeartbeatIntervalSeconds(currentConfig),
    offlineTimeoutSeconds: Math.floor(offlineTimeoutMs / 1000),
    checkedAt: new Date(nowMs).toISOString(),
    storageBackend: 'memory'
  };
}

function buildPresenceSnapshot(currentConfig, nowMs = Date.now()) {
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  const summary = buildPresenceSummary(currentConfig, nowMs);
  const sessions = Array.from(presenceSessions.values())
    .map((session) => {
      const firstSeenAtMs = Number(session.firstSeenAtMs || 0);
      const lastSeenAtMs = Number(session.lastSeenAtMs || 0);
      return {
        clientId: session.clientId || '',
        deviceId: session.deviceId || '',
        idType: session.idType || '',
        state: session.state || 'unknown',
        playerName: session.playerName || '',
        appVersion: session.appVersion || '',
        firstSeenAt: firstSeenAtMs > 0 ? new Date(firstSeenAtMs).toISOString() : null,
        lastSeenAt: lastSeenAtMs > 0 ? new Date(lastSeenAtMs).toISOString() : null,
        ageSeconds: lastSeenAtMs > 0 ? Math.max(0, Math.floor((nowMs - lastSeenAtMs) / 1000)) : null,
        expiresInSeconds: lastSeenAtMs > 0
          ? Math.max(0, Math.ceil((offlineTimeoutMs - (nowMs - lastSeenAtMs)) / 1000))
          : 0
      };
    })
    .sort((left, right) => String(right.lastSeenAt || '').localeCompare(String(left.lastSeenAt || '')));

  return {
    ...summary,
    sessions
  };
}

function buildPresenceStats(currentConfig, options = {}, nowMs = Date.now()) {
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  const bucketSeconds = resolveStatsBucketSeconds(options);
  const bucketMs = bucketSeconds * 1000;
  const windowMs = STATS_WINDOW_SECONDS * 1000;
  const sinceMs = nowMs - windowMs;
  pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs);
  prunePresenceHeartbeatHistory(nowMs, windowMs + offlineTimeoutMs);

  const windowEvents = presenceHeartbeatHistory.filter((event) =>
    event.atMs >= sinceMs && event.atMs <= nowMs
  );
  const uniqueClients = new Set(windowEvents.map((event) => event.clientId));
  const buckets = [];
  let peakOnline = 0;

  for (let bucketStartMs = sinceMs; bucketStartMs < nowMs; bucketStartMs += bucketMs) {
    const bucketEndMs = Math.min(nowMs, bucketStartMs + bucketMs);
    const isLastBucket = bucketEndMs >= nowMs;
    const bucketEvents = windowEvents.filter((event) =>
      event.atMs >= bucketStartMs &&
      (event.atMs < bucketEndMs || (isLastBucket && event.atMs <= bucketEndMs))
    );
    const bucketUniqueClients = new Set(bucketEvents.map((event) => event.clientId));
    const onlineClients = new Set();
    for (const event of presenceHeartbeatHistory) {
      if (event.atMs <= bucketEndMs && event.atMs > bucketEndMs - offlineTimeoutMs) {
        onlineClients.add(event.clientId);
      }
    }
    peakOnline = Math.max(peakOnline, onlineClients.size);
    buckets.push({
      bucketStart: new Date(bucketStartMs).toISOString(),
      bucketEnd: new Date(bucketEndMs).toISOString(),
      online: onlineClients.size,
      uniqueClients: bucketUniqueClients.size,
      heartbeats: bucketEvents.length
    });
  }

  return {
    windowSeconds: STATS_WINDOW_SECONDS,
    bucketSeconds,
    since: new Date(sinceMs).toISOString(),
    until: new Date(nowMs).toISOString(),
    currentOnline: presenceSessions.size,
    peakOnline,
    uniqueClients: uniqueClients.size,
    totalHeartbeats: windowEvents.length,
    buckets
  };
}

function pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs) {
  for (const [clientId, session] of presenceSessions.entries()) {
    if (!session || nowMs - Number(session.lastSeenAtMs || 0) >= offlineTimeoutMs) {
      presenceSessions.delete(clientId);
    }
  }
}

function recordPresenceHeartbeatHistory(heartbeat, nowMs, currentConfig) {
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  presenceHeartbeatHistory.push({
    clientId: heartbeat.clientId,
    state: heartbeat.state || 'unknown',
    atMs: nowMs
  });
  prunePresenceHeartbeatHistory(nowMs, (STATS_WINDOW_SECONDS * 1000) + offlineTimeoutMs);
}

function prunePresenceHeartbeatHistory(nowMs, retentionMs) {
  const cutoffMs = nowMs - retentionMs;
  while (
    presenceHeartbeatHistory.length > 0 &&
    Number(presenceHeartbeatHistory[0].atMs || 0) < cutoffMs
  ) {
    presenceHeartbeatHistory.shift();
  }
}

function resolveHeartbeatIntervalSeconds(currentConfig) {
  return Number(currentConfig && currentConfig.presenceHeartbeatIntervalSeconds) ||
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
}

function resolveOfflineTimeoutMs(currentConfig) {
  const seconds = Number(currentConfig && currentConfig.presenceOfflineTimeoutSeconds) ||
    DEFAULT_OFFLINE_TIMEOUT_SECONDS;
  return seconds * 1000;
}

function resolveStatsBucketSeconds(options) {
  const rawValue = options && firstNonEmpty(options.bucket_seconds, options.bucketSeconds);
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return DEFAULT_STATS_BUCKET_SECONDS;
  }
  return Math.max(
    MIN_STATS_BUCKET_SECONDS,
    Math.min(MAX_STATS_BUCKET_SECONDS, parsed)
  );
}

function normalizeClientId(value) {
  const normalized = normalizeOptionalString(value);
  if (!normalized) {
    return '';
  }
  return normalized.slice(0, MAX_CLIENT_ID_LENGTH);
}

function normalizeOptionalString(value) {
  return String(value || '').trim();
}

function resetPresenceStoreForTest() {
  presenceSessions.clear();
  presenceHeartbeatHistory.length = 0;
}

module.exports = {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS,
  parsePresenceHeartbeatRequest,
  recordPresenceHeartbeat,
  buildPresenceSummary,
  buildPresenceSnapshot,
  buildPresenceStats,
  resetPresenceStoreForTest
};
