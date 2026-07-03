'use strict';

const path = require('path');

const DEFAULT_PORT = 8787;
const DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
const DEFAULT_OFFLINE_TIMEOUT_SECONDS = 90;
const DEFAULT_QQ_GROUP_NUMBER = '1029305387';

function loadConfig(env = process.env) {
  const heartbeatIntervalSeconds = parsePositiveInteger(
    env.PRESENCE_HEARTBEAT_INTERVAL_SECONDS,
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS
  );

  return {
    host: firstNonEmpty(env.HOST, '0.0.0.0'),
    port: parsePositiveInteger(env.PORT, DEFAULT_PORT),
    publicBaseUrl: normalizeOptionalBaseUrl(env.PUBLIC_BASE_URL),
    dbPath: path.resolve(firstNonEmpty(env.PRESENCE_DB_PATH, './data/presence.sqlite')),
    presenceHeartbeatIntervalSeconds: heartbeatIntervalSeconds,
    presenceOfflineTimeoutSeconds: parsePositiveInteger(
      env.PRESENCE_OFFLINE_TIMEOUT_SECONDS,
      Math.max(DEFAULT_OFFLINE_TIMEOUT_SECONDS, heartbeatIntervalSeconds * 3)
    ),
    qqGroupNumber: normalizeQqGroupNumber(env.QQ_GROUP_NUMBER, DEFAULT_QQ_GROUP_NUMBER),
    presencePanelToken: firstNonEmpty(env.PRESENCE_PANEL_TOKEN, env.FEEDBACK_SHARED_SECRET),
    logLevel: firstNonEmpty(env.LOG_LEVEL, 'info'),
    maxSessionsReturned: parsePositiveInteger(env.PRESENCE_MAX_SESSIONS_RETURNED, 1000),
    panelSnapshotPushIntervalSeconds: parsePositiveInteger(
      env.PRESENCE_PANEL_SNAPSHOT_PUSH_INTERVAL_SECONDS,
      2
    ),
    panelStatsPushIntervalSeconds: parsePositiveInteger(
      env.PRESENCE_PANEL_STATS_PUSH_INTERVAL_SECONDS,
      300
    )
  };
}

function parsePositiveInteger(rawValue, fallbackValue) {
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallbackValue;
}

function firstNonEmpty(...values) {
  for (const value of values) {
    const normalized = String(value || '').trim();
    if (normalized) {
      return normalized;
    }
  }
  return '';
}

function normalizeQqGroupNumber(rawValue, fallbackValue) {
  const normalized = firstNonEmpty(rawValue);
  return /^[1-9][0-9]{4,19}$/.test(normalized) ? normalized : fallbackValue;
}

function normalizeOptionalBaseUrl(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    return '';
  }
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
}

module.exports = {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS,
  DEFAULT_QQ_GROUP_NUMBER,
  loadConfig,
  parsePositiveInteger,
  firstNonEmpty,
  normalizeQqGroupNumber
};
