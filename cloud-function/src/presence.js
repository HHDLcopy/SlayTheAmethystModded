'use strict';

const { firstNonEmpty, httpError } = require('./utils');

const DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 240;
const DEFAULT_OFFLINE_TIMEOUT_SECONDS = 500;
const MAX_CLIENT_ID_LENGTH = 128;
const presenceSessions = new Map();

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
    appVersion: normalizeOptionalString(firstNonEmpty(body.app_version, body.appVersion)),
    processName: normalizeOptionalString(firstNonEmpty(body.process, body.process_name, body.processName)),
    launchMode: normalizeOptionalString(firstNonEmpty(body.launch_mode, body.launchMode))
  };
}

function recordPresenceHeartbeat(heartbeat, currentConfig) {
  const nowMs = Date.now();
  const offlineTimeoutMs = resolveOfflineTimeoutMs(currentConfig);
  pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs);

  const existing = presenceSessions.get(heartbeat.clientId);
  const session = {
    clientId: heartbeat.clientId,
    deviceId: heartbeat.deviceId,
    idType: heartbeat.idType,
    state: heartbeat.state,
    appVersion: heartbeat.appVersion,
    processName: heartbeat.processName,
    launchMode: heartbeat.launchMode,
    firstSeenAtMs: existing ? existing.firstSeenAtMs : nowMs,
    lastSeenAtMs: nowMs
  };
  presenceSessions.set(heartbeat.clientId, session);

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
    checkedAt: new Date(nowMs).toISOString()
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
        appVersion: session.appVersion || '',
        processName: session.processName || '',
        launchMode: session.launchMode || '',
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

function pruneExpiredPresenceSessions(nowMs, offlineTimeoutMs) {
  for (const [clientId, session] of presenceSessions.entries()) {
    if (!session || nowMs - Number(session.lastSeenAtMs || 0) >= offlineTimeoutMs) {
      presenceSessions.delete(clientId);
    }
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
}

module.exports = {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS,
  parsePresenceHeartbeatRequest,
  recordPresenceHeartbeat,
  buildPresenceSummary,
  buildPresenceSnapshot,
  resetPresenceStoreForTest
};
