'use strict';

const {
  firstNonEmpty,
  httpError,
  parsePositiveInteger,
  summarizeErrorWithCause
} = require('./utils');
const {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS
} = require('./presence');

const DEFAULT_STORAGE_TIMEOUT_MS = 3000;

function hasPresenceStorage(currentConfig) {
  return Boolean(resolvePresenceStorageUrl(currentConfig));
}

async function recordPresenceHeartbeatInStorage(heartbeat, currentConfig) {
  return callPresenceStorage(currentConfig, {
    method: 'POST',
    path: '/internal/presence/heartbeat',
    body: {
      client_id: heartbeat.clientId,
      device_id: heartbeat.deviceId,
      id_type: heartbeat.idType,
      state: heartbeat.state,
      player_name: heartbeat.playerName,
      app_version: heartbeat.appVersion,
      ...buildPresenceRuntimeOptions(currentConfig)
    }
  });
}

async function fetchPresenceSummaryFromStorage(currentConfig) {
  return callPresenceStorage(currentConfig, {
    method: 'GET',
    path: '/internal/presence/summary',
    query: buildPresenceRuntimeOptions(currentConfig)
  });
}

async function fetchPresenceSnapshotFromStorage(currentConfig) {
  return callPresenceStorage(currentConfig, {
    method: 'GET',
    path: '/internal/presence/sessions',
    query: buildPresenceRuntimeOptions(currentConfig)
  });
}

async function fetchPresenceStatsFromStorage(currentConfig, options) {
  return callPresenceStorage(currentConfig, {
    method: 'GET',
    path: '/internal/presence/stats',
    query: {
      ...buildPresenceRuntimeOptions(currentConfig),
      bucket_seconds: firstNonEmpty(options && options.bucket_seconds, options && options.bucketSeconds)
    }
  });
}

async function callPresenceStorage(currentConfig, request) {
  const baseUrl = resolvePresenceStorageUrl(currentConfig);
  if (!baseUrl) {
    throw httpError(503, 'Presence storage URL is not configured');
  }

  const secret = resolvePresenceStorageSecret(currentConfig);
  if (!secret) {
    throw httpError(503, 'PRESENCE_STORAGE_SECRET must be configured when PRESENCE_STORAGE_URL is set');
  }

  const requestUrl = buildPresenceStorageUrl(baseUrl, request.path, request.query);
  const timeoutMs = resolvePresenceStorageTimeoutMs(currentConfig);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);

  let response;
  try {
    response = await fetch(requestUrl, {
      method: request.method,
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${secret}`,
        ...(request.body ? { 'Content-Type': 'application/json' } : {})
      },
      body: request.body ? JSON.stringify(request.body) : undefined,
      signal: controller.signal
    });
  } catch (error) {
    throw httpError(
      502,
      `Presence storage request to ${requestUrl.host} failed: ${summarizeErrorWithCause(error) || 'fetch failed'}`
    );
  } finally {
    clearTimeout(timeout);
  }

  const rawText = await response.text();
  const body = safeJsonParse(rawText);
  if (!response.ok) {
    const detail = body && body.message
      ? body.message
      : rawText.trim();
    throw httpError(502, `Presence storage returned HTTP ${response.status}${detail ? `: ${detail}` : ''}`);
  }
  if (!body || typeof body !== 'object') {
    throw httpError(502, 'Presence storage returned an invalid JSON response');
  }
  return body;
}

function buildPresenceStorageUrl(baseUrl, path, query) {
  const url = new URL(path, normalizeBaseUrl(baseUrl));
  for (const [key, value] of Object.entries(query || {})) {
    const normalized = String(value || '').trim();
    if (normalized) {
      url.searchParams.set(key, normalized);
    }
  }
  return url;
}

function normalizeBaseUrl(baseUrl) {
  const normalized = String(baseUrl || '').trim();
  return normalized.endsWith('/') ? normalized : `${normalized}/`;
}

function buildPresenceRuntimeOptions(currentConfig) {
  return {
    heartbeat_interval_seconds: Number(currentConfig && currentConfig.presenceHeartbeatIntervalSeconds) ||
      DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
    offline_timeout_seconds: Number(currentConfig && currentConfig.presenceOfflineTimeoutSeconds) ||
      DEFAULT_OFFLINE_TIMEOUT_SECONDS
  };
}

function resolvePresenceStorageUrl(currentConfig) {
  return String(currentConfig && currentConfig.presenceStorageUrl || '').trim();
}

function resolvePresenceStorageSecret(currentConfig) {
  return String(currentConfig && currentConfig.presenceStorageSecret || '').trim();
}

function resolvePresenceStorageTimeoutMs(currentConfig) {
  return parsePositiveInteger(
    currentConfig && currentConfig.presenceStorageTimeoutMs,
    DEFAULT_STORAGE_TIMEOUT_MS
  );
}

function safeJsonParse(rawText) {
  if (!String(rawText || '').trim()) {
    return {};
  }
  try {
    return JSON.parse(rawText);
  } catch (_error) {
    return null;
  }
}

module.exports = {
  hasPresenceStorage,
  recordPresenceHeartbeatInStorage,
  fetchPresenceSummaryFromStorage,
  fetchPresenceSnapshotFromStorage,
  fetchPresenceStatsFromStorage
};
