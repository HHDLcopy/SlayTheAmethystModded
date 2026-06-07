'use strict';

const { APP_NAME } = require('./constants');
const {
  escapeHtml,
  escapeHtmlAttribute,
  firstNonEmpty,
  httpError
} = require('./utils');

function enforcePresencePanelAccess(req, currentConfig) {
  const requiredToken = resolvePresencePanelToken(currentConfig);
  if (!requiredToken) {
    throw httpError(503, 'Presence panel token not configured');
  }

  const providedToken = firstNonEmpty(
    req.get('x-presence-panel-token'),
    req.get('x-feedback-key'),
    req.query && req.query.token,
    req.query && req.query.key
  );
  if (providedToken !== requiredToken) {
    throw httpError(401, 'Invalid presence panel token');
  }
}

function renderPresencePanel(snapshot, currentConfig, req) {
  const token = firstNonEmpty(req.query && req.query.token, req.query && req.query.key);
  const tokenQuery = token ? `?token=${encodeURIComponent(token)}` : '';
  const panelUrl = `${escapeHtmlAttribute(req.path || '/presence')}${escapeHtmlAttribute(tokenQuery)}`;
  const sessionsApiUrl = `/api/presence/sessions${tokenQuery}`;
  const initialSnapshotJson = escapeJsonForScript(snapshot);
  const stateRows = Object.entries(snapshot.byState || {})
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([state, count]) => `
          <tr>
            <td>${escapeHtml(state)}</td>
            <td class="number">${Number(count) || 0}</td>
          </tr>`)
    .join('');
  const sessionRows = Array.isArray(snapshot.sessions) && snapshot.sessions.length > 0
    ? snapshot.sessions.map(renderSessionRow).join('')
    : `
            <tr>
              <td class="empty" colspan="8">No active game sessions.</td>
            </tr>`;

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Presence Panel - ${escapeHtml(APP_NAME)}</title>
  <style>
    :root {
      color-scheme: light dark;
      --bg: #f6f7f9;
      --panel: #ffffff;
      --text: #151922;
      --muted: #5f6878;
      --line: #d9dee7;
      --accent: #1664d9;
      --accent-soft: #e8f0ff;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg: #11151c;
        --panel: #181e27;
        --text: #eef2f8;
        --muted: #a9b3c2;
        --line: #2b3442;
        --accent: #7aa7ff;
        --accent-soft: #1d2b45;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: var(--bg);
      color: var(--text);
      font: 14px/1.5 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    main {
      width: min(1180px, calc(100vw - 32px));
      margin: 28px auto;
    }
    header {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      align-items: flex-end;
      margin-bottom: 18px;
    }
    h1 {
      font-size: 24px;
      line-height: 1.2;
      margin: 0 0 6px;
      letter-spacing: 0;
    }
    .subtle { color: var(--muted); }
    .actions {
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
      justify-content: flex-end;
    }
    .button {
      display: inline-flex;
      align-items: center;
      min-height: 36px;
      padding: 0 12px;
      border: 1px solid var(--line);
      border-radius: 6px;
      color: var(--text);
      background: var(--panel);
      text-decoration: none;
    }
    .grid {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }
    .metric, .section {
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
    }
    .metric {
      padding: 16px;
      min-height: 92px;
    }
    .metric-label {
      color: var(--muted);
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: .08em;
    }
    .metric-value {
      font-size: 30px;
      line-height: 1.2;
      margin-top: 8px;
      font-weight: 700;
    }
    .section {
      overflow: hidden;
      margin-top: 16px;
    }
    .section-header {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      padding: 14px 16px;
      border-bottom: 1px solid var(--line);
      background: color-mix(in srgb, var(--panel), var(--bg) 28%);
    }
    .section-title {
      font-weight: 650;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th, td {
      padding: 10px 12px;
      border-bottom: 1px solid var(--line);
      text-align: left;
      vertical-align: top;
      white-space: nowrap;
    }
    th {
      color: var(--muted);
      font-size: 12px;
      font-weight: 650;
      background: color-mix(in srgb, var(--panel), var(--bg) 18%);
    }
    tr:last-child td { border-bottom: 0; }
    code {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 12px;
      color: var(--accent);
      background: var(--accent-soft);
      border-radius: 4px;
      padding: 2px 4px;
    }
    .number { text-align: right; }
    .empty {
      text-align: center;
      color: var(--muted);
      padding: 28px 12px;
    }
    .table-wrap {
      overflow-x: auto;
    }
    @media (max-width: 820px) {
      header {
        align-items: flex-start;
        flex-direction: column;
      }
      .actions {
        justify-content: flex-start;
      }
      .grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    @media (max-width: 520px) {
      main {
        width: min(100vw - 20px, 1180px);
        margin: 18px auto;
      }
      .grid {
        grid-template-columns: 1fr;
      }
      th, td {
        padding: 9px 10px;
      }
    }
  </style>
</head>
<body>
  <main>
    <header>
      <div>
        <h1>在线情况面板</h1>
        <div class="subtle" id="panel-subtitle">${escapeHtml(APP_NAME)} · 自动每 15 秒同步 · ${escapeHtml(snapshot.checkedAt || '')}</div>
      </div>
      <div class="actions">
        <a class="button" id="refresh-now" href="${panelUrl}">刷新</a>
      </div>
    </header>

    <section class="grid" aria-label="Presence summary">
      <div class="metric">
        <div class="metric-label">当前在线</div>
        <div class="metric-value" id="metric-online">${Number(snapshot.online) || 0}</div>
      </div>
      <div class="metric">
        <div class="metric-label">心跳间隔</div>
        <div class="metric-value" id="metric-heartbeat">${Number(snapshot.heartbeatIntervalSeconds) || 0}s</div>
      </div>
      <div class="metric">
        <div class="metric-label">离线阈值</div>
        <div class="metric-value" id="metric-timeout">${Number(snapshot.offlineTimeoutSeconds) || 0}s</div>
      </div>
      <div class="metric">
        <div class="metric-label">存储后端</div>
        <div class="metric-value">Memory</div>
      </div>
    </section>

    <section class="section">
      <div class="section-header">
        <div class="section-title">状态统计</div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>状态</th>
              <th class="number">人数</th>
            </tr>
          </thead>
          <tbody id="state-rows">
${stateRows || '          <tr><td class="empty" colspan="2">No active states.</td></tr>'}
          </tbody>
        </table>
      </div>
    </section>

    <section class="section">
      <div class="section-header">
        <div class="section-title">在线会话</div>
        <div class="subtle">按最近心跳倒序</div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>设备</th>
              <th>玩家名</th>
              <th>ID 类型</th>
              <th>状态</th>
              <th>版本</th>
              <th>首次在线</th>
              <th>最近心跳</th>
              <th>剩余 TTL</th>
            </tr>
          </thead>
          <tbody id="session-rows">
${sessionRows}
          </tbody>
        </table>
      </div>
    </section>
  </main>
  <script>
    (function () {
      'use strict';

      var appName = ${JSON.stringify(APP_NAME)};
      var sessionsApiUrl = ${JSON.stringify(sessionsApiUrl)};
      var refreshButton = document.getElementById('refresh-now');
      var stateRows = document.getElementById('state-rows');
      var sessionRows = document.getElementById('session-rows');
      var subtitle = document.getElementById('panel-subtitle');
      var metricOnline = document.getElementById('metric-online');
      var metricHeartbeat = document.getElementById('metric-heartbeat');
      var metricTimeout = document.getElementById('metric-timeout');
      var refreshTimer = null;

      function escapeHtml(value) {
        return String(value || '')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#39;');
      }

      function escapeHtmlAttribute(value) {
        return escapeHtml(value).replace(new RegExp(String.fromCharCode(96), 'g'), '&#96;');
      }

      function maskIdentifier(value) {
        var normalized = String(value || '').trim();
        if (normalized.length <= 24) {
          return normalized || 'unknown';
        }
        return normalized.slice(0, 14) + '...' + normalized.slice(-8);
      }

      function formatDateTime(value) {
        if (!value) {
          return '-';
        }
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) {
          return '-';
        }
        return date.toLocaleString('zh-CN', {
          hour12: false,
          timeZone: 'Asia/Hong_Kong'
        });
      }

      function formatAge(value) {
        var seconds = Number(value);
        if (!Number.isFinite(seconds)) {
          return '-';
        }
        return Math.max(0, seconds) + 's ago';
      }

      function renderStateRows(byState) {
        var entries = Object.entries(byState || {})
          .sort(function (left, right) {
            return left[0].localeCompare(right[0]);
          });
        if (entries.length === 0) {
          return '<tr><td class="empty" colspan="2">No active states.</td></tr>';
        }
        return entries.map(function (entry) {
          return '<tr><td>' + escapeHtml(entry[0]) + '</td><td class="number">' + (Number(entry[1]) || 0) + '</td></tr>';
        }).join('');
      }

      function renderSessionRows(sessions) {
        if (!Array.isArray(sessions) || sessions.length === 0) {
          return '<tr><td class="empty" colspan="8">No active game sessions.</td></tr>';
        }
        return sessions.map(function (session) {
          var clientId = session.clientId || '';
          var deviceLabel = maskIdentifier(clientId || session.deviceId || 'unknown');
          var playerName = String(session.playerName || '').trim() || '-';
          return '<tr>' +
            '<td><code title="' + escapeHtmlAttribute(clientId) + '">' + escapeHtml(deviceLabel) + '</code></td>' +
            '<td>' + escapeHtml(playerName) + '</td>' +
            '<td>' + escapeHtml(session.idType || 'unknown') + '</td>' +
            '<td>' + escapeHtml(session.state || 'unknown') + '</td>' +
            '<td>' + escapeHtml(session.appVersion || '-') + '</td>' +
            '<td>' + escapeHtml(formatDateTime(session.firstSeenAt)) + '</td>' +
            '<td>' + escapeHtml(formatDateTime(session.lastSeenAt)) + '<br><span class="subtle">' + escapeHtml(formatAge(session.ageSeconds)) + '</span></td>' +
            '<td>' + (Number(session.expiresInSeconds) || 0) + 's</td>' +
          '</tr>';
        }).join('');
      }

      function renderSnapshot(snapshot) {
        metricOnline.textContent = String(Number(snapshot.online) || 0);
        metricHeartbeat.textContent = String(Number(snapshot.heartbeatIntervalSeconds) || 0) + 's';
        metricTimeout.textContent = String(Number(snapshot.offlineTimeoutSeconds) || 0) + 's';
        subtitle.textContent = appName + ' · 自动每 15 秒同步 · ' + (snapshot.checkedAt || '');
        stateRows.innerHTML = renderStateRows(snapshot.byState || {});
        sessionRows.innerHTML = renderSessionRows(snapshot.sessions || []);
      }

      function loadSnapshot() {
        return fetch(sessionsApiUrl, {
          cache: 'no-store',
          headers: {
            Accept: 'application/json'
          }
        })
          .then(function (response) {
            if (!response.ok) {
              throw new Error('HTTP ' + response.status);
            }
            return response.json();
          })
          .then(function (snapshot) {
            renderSnapshot(snapshot);
          })
          .catch(function (error) {
            subtitle.textContent = appName + ' · 同步失败 · ' + (error && error.message ? error.message : 'unknown');
          });
      }

      if (refreshButton) {
        refreshButton.addEventListener('click', function (event) {
          event.preventDefault();
          loadSnapshot();
        });
      }

      renderSnapshot(${initialSnapshotJson});
      refreshTimer = window.setInterval(loadSnapshot, 15000);
      window.addEventListener('beforeunload', function () {
        if (refreshTimer) {
          window.clearInterval(refreshTimer);
        }
      });
    }());
  </script>
</body>
</html>`;
}

function renderPresencePanelUnauthorized(req, currentConfig) {
  const configured = Boolean(resolvePresencePanelToken(currentConfig));
  const action = escapeHtmlAttribute(req.path || '/presence');
  const message = configured
    ? '请输入面板访问令牌。'
    : '未配置 PRESENCE_PANEL_TOKEN，也没有可回退的 FEEDBACK_SHARED_SECRET。';

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Presence Panel Login</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      background: #f6f7f9;
      color: #151922;
      font: 14px/1.5 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    form {
      width: min(420px, calc(100vw - 32px));
      background: #fff;
      border: 1px solid #d9dee7;
      border-radius: 8px;
      padding: 22px;
    }
    h1 {
      margin: 0 0 10px;
      font-size: 22px;
      letter-spacing: 0;
    }
    p {
      margin: 0 0 16px;
      color: #5f6878;
    }
    label {
      display: block;
      font-weight: 650;
      margin-bottom: 6px;
    }
    input {
      width: 100%;
      min-height: 38px;
      border: 1px solid #c9d0dc;
      border-radius: 6px;
      padding: 0 10px;
      font: inherit;
      margin-bottom: 14px;
    }
    button {
      min-height: 38px;
      border: 0;
      border-radius: 6px;
      background: #1664d9;
      color: #fff;
      padding: 0 14px;
      font: inherit;
      font-weight: 650;
    }
  </style>
</head>
<body>
  <form method="get" action="${action}">
    <h1>在线情况面板</h1>
    <p>${escapeHtml(message)}</p>
    ${configured ? '<label for="token">访问令牌</label><input id="token" name="token" type="password" autocomplete="current-password" autofocus><button type="submit">进入</button>' : ''}
  </form>
</body>
</html>`;
}

function renderSessionRow(session) {
  return `
          <tr>
            <td><code title="${escapeHtmlAttribute(session.clientId || '')}">${escapeHtml(maskIdentifier(session.clientId || session.deviceId || 'unknown'))}</code></td>
            <td>${escapeHtml(session.playerName || '-')}</td>
            <td>${escapeHtml(session.idType || 'unknown')}</td>
            <td>${escapeHtml(session.state || 'unknown')}</td>
            <td>${escapeHtml(session.appVersion || '-')}</td>
            <td>${escapeHtml(formatDateTime(session.firstSeenAt))}</td>
            <td>${escapeHtml(formatDateTime(session.lastSeenAt))}<br><span class="subtle">${formatAge(session.ageSeconds)}</span></td>
            <td>${Number(session.expiresInSeconds) || 0}s</td>
          </tr>`;
}

function escapeJsonForScript(value) {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
}

function resolvePresencePanelToken(currentConfig) {
  return firstNonEmpty(
    currentConfig && currentConfig.presencePanelToken,
    currentConfig && currentConfig.sharedSecret
  );
}

function maskIdentifier(value) {
  const normalized = String(value || '').trim();
  if (normalized.length <= 24) {
    return normalized || 'unknown';
  }
  return `${normalized.slice(0, 14)}...${normalized.slice(-8)}`;
}

function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString('zh-CN', {
    hour12: false,
    timeZone: 'Asia/Hong_Kong'
  });
}

function formatAge(value) {
  if (!Number.isFinite(Number(value))) {
    return '-';
  }
  return `${Math.max(0, Number(value))}s ago`;
}

module.exports = {
  enforcePresencePanelAccess,
  renderPresencePanel,
  renderPresencePanelUnauthorized
};
