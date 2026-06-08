(function () {
  'use strict';

  const { createApp, computed, onBeforeUnmount, onMounted, reactive } = Vue;

  function buildPanelWebSocketUrl(token) {
    const url = new URL('/api/presence/panel/ws', window.location.href);
    url.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    if (token) {
      url.searchParams.set('token', token);
    }
    return url.toString();
  }

  function readTokenFromLocation() {
    const params = new URLSearchParams(window.location.search);
    return (params.get('token') || params.get('key') || '').trim();
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

  function formatShortDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '-';
    }
    const month = String(date.getMonth() + 1);
    const day = String(date.getDate());
    const hour = String(date.getHours()).padStart(2, '0');
    return month + '/' + day + ' ' + hour + ':00';
  }

  function formatAge(value) {
    const seconds = Number(value);
    if (!Number.isFinite(seconds)) {
      return '-';
    }
    return Math.max(0, seconds) + 's ago';
  }

  function maskIdentifier(value) {
    const normalized = String(value || '').trim();
    if (!normalized) {
      return 'unknown';
    }
    if (normalized.length <= 24) {
      return normalized;
    }
    return normalized.slice(0, 14) + '...' + normalized.slice(-8);
  }

  createApp({
    setup() {
      const state = reactive({
        token: readTokenFromLocation(),
        inputToken: readTokenFromLocation(),
        ws: null,
        reconnectTimer: null,
        reconnectAttempt: 0,
        manuallyClosed: false,
        connectionStatus: 'idle',
        connectionMessage: '未连接',
        config: null,
        snapshot: null,
        stats: null,
        lastError: ''
      });

      const hasToken = computed(() => state.token.trim().length > 0);
      const isConnected = computed(() => state.connectionStatus === 'connected');
      const isError = computed(() => state.connectionStatus === 'error');
      const snapshot = computed(() => state.snapshot || {
        online: 0,
        byState: {},
        heartbeatIntervalSeconds: 0,
        offlineTimeoutSeconds: 0,
        checkedAt: '',
        storageBackend: 'sqlite3',
        totalDevices: 0,
        sessions: []
      });
      const stats = computed(() => state.stats || {
        peakOnline: 0,
        currentOnline: 0,
        snapshotCount: 0,
        buckets: [],
        since: '',
        until: ''
      });
      const stateRows = computed(() => Object.entries(snapshot.value.byState || {})
        .sort((left, right) => left[0].localeCompare(right[0])));
      const chart = computed(() => buildChart(stats.value.buckets || []));

      function login() {
        const token = state.inputToken.trim();
        if (!token) {
          return;
        }
        const nextUrl = new URL(window.location.href);
        nextUrl.searchParams.set('token', token);
        window.history.replaceState(null, '', nextUrl.toString());
        state.token = token;
        connect();
      }

      function connect() {
        disconnect(false);
        if (!hasToken.value) {
          state.connectionStatus = 'idle';
          state.connectionMessage = '等待令牌';
          return;
        }

        state.manuallyClosed = false;
        state.connectionStatus = 'connecting';
        state.connectionMessage = '连接中';
        const ws = new WebSocket(buildPanelWebSocketUrl(state.token));
        state.ws = ws;

        ws.addEventListener('open', () => {
          state.reconnectAttempt = 0;
          state.connectionStatus = 'connected';
          state.connectionMessage = 'WS 已连接';
        });
        ws.addEventListener('message', (event) => {
          handleMessage(event.data);
        });
        ws.addEventListener('close', () => {
          if (state.ws === ws) {
            state.ws = null;
          }
          if (!state.manuallyClosed) {
            scheduleReconnect();
          }
        });
        ws.addEventListener('error', () => {
          state.connectionStatus = 'error';
          state.connectionMessage = '连接异常';
        });
      }

      function disconnect(markManual) {
        if (state.reconnectTimer) {
          window.clearTimeout(state.reconnectTimer);
          state.reconnectTimer = null;
        }
        state.manuallyClosed = Boolean(markManual);
        const ws = state.ws;
        state.ws = null;
        if (ws) {
          try {
            ws.close(1000, 'panel reconnect');
          } catch (_error) {
          }
        }
      }

      function scheduleReconnect() {
        state.connectionStatus = 'error';
        const delayMs = Math.min(30000, 1000 * Math.pow(2, Math.min(5, state.reconnectAttempt)));
        state.reconnectAttempt += 1;
        state.connectionMessage = '断开，' + Math.ceil(delayMs / 1000) + 's 后重连';
        state.reconnectTimer = window.setTimeout(connect, delayMs);
      }

      function handleMessage(rawText) {
        let message;
        try {
          message = JSON.parse(String(rawText || '{}'));
        } catch (_error) {
          return;
        }
        if (!message || typeof message !== 'object') {
          return;
        }
        if (message.type === 'config') {
          state.config = message;
          return;
        }
        if (message.type === 'snapshot') {
          state.snapshot = message.data || null;
          return;
        }
        if (message.type === 'stats') {
          state.stats = message.data || null;
          return;
        }
        if (message.type === 'error') {
          state.lastError = message.message || 'unknown';
          state.connectionStatus = 'error';
          state.connectionMessage = state.lastError;
        }
      }

      function send(type) {
        const ws = state.ws;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          connect();
          return;
        }
        ws.send(JSON.stringify({ type }));
      }

      function refreshAll() {
        send('refresh');
        send('refresh_stats');
      }

      onMounted(() => {
        if (hasToken.value) {
          connect();
        }
      });

      onBeforeUnmount(() => {
        disconnect(true);
      });

      return {
        state,
        hasToken,
        isConnected,
        isError,
        snapshot,
        stats,
        stateRows,
        chart,
        login,
        refreshAll,
        formatAge,
        formatDateTime,
        formatShortDateTime,
        maskIdentifier
      };
    },
    template: `
      <div v-if="!hasToken" class="login-wrap">
        <form class="login" @submit.prevent="login">
          <h1>在线情况面板</h1>
          <p>请输入面板访问令牌。</p>
          <label for="token">访问令牌</label>
          <input id="token" v-model="state.inputToken" type="password" autocomplete="current-password" autofocus>
          <button class="primary" type="submit">进入</button>
        </form>
      </div>

      <main v-else class="page">
        <header class="topbar">
          <div>
            <h1>在线情况面板</h1>
            <div class="muted">SlayTheAmethyst · WebSocket 实时同步 · {{ snapshot.checkedAt || '-' }}</div>
          </div>
          <div class="actions">
            <div class="status-pill">
              <span class="dot" :class="{ connected: isConnected, error: isError }"></span>
              <span>{{ state.connectionMessage }}</span>
            </div>
            <button type="button" @click="refreshAll">刷新</button>
          </div>
        </header>

        <section class="metrics" aria-label="Presence summary">
          <div class="metric">
            <div class="metric-label">当前在线</div>
            <div class="metric-value">{{ Number(snapshot.online) || 0 }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">心跳间隔</div>
            <div class="metric-value">{{ Number(snapshot.heartbeatIntervalSeconds) || 0 }}s</div>
          </div>
          <div class="metric">
            <div class="metric-label">离线阈值</div>
            <div class="metric-value">{{ Number(snapshot.offlineTimeoutSeconds) || 0 }}s</div>
          </div>
          <div class="metric">
            <div class="metric-label">存储后端</div>
            <div class="metric-value">{{ snapshot.storageBackend || 'sqlite3' }}</div>
          </div>
        </section>

        <section class="section">
          <div class="section-header">
            <div class="section-title">一周在线趋势</div>
            <div class="muted">{{ formatShortDateTime(stats.since) }} - {{ formatShortDateTime(stats.until) }}</div>
          </div>
          <div class="chart-tools">
            <span>峰值 <strong>{{ Number(stats.peakOnline) || 0 }}</strong></span>
            <span>当前 <strong>{{ Number(stats.currentOnline) || 0 }}</strong></span>
            <span>样本 <strong>{{ Number(stats.snapshotCount) || 0 }}/{{ (stats.buckets || []).length }}</strong></span>
          </div>
          <div class="chart">
            <div v-if="!chart.hasSamples" class="empty">No weekly presence snapshots yet.</div>
            <svg v-else viewBox="0 0 720 240" role="img" aria-label="最近一周在线人数折线图" preserveAspectRatio="none">
              <line v-for="line in chart.gridLines" class="chart-grid" :x1="40" :x2="700" :y1="line.y" :y2="line.y"></line>
              <path class="chart-area" :d="chart.areaPath"></path>
              <polyline class="chart-line" :points="chart.points"></polyline>
              <text class="chart-axis" x="40" y="228">{{ chart.firstLabel }}</text>
              <text class="chart-axis" x="620" y="228">{{ chart.lastLabel }}</text>
              <text class="chart-axis" x="8" y="28">{{ chart.maxOnline }}</text>
              <text class="chart-axis" x="20" y="204">0</text>
            </svg>
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
              <tbody>
                <tr v-if="stateRows.length === 0">
                  <td class="empty" colspan="2">No active states.</td>
                </tr>
                <tr v-for="row in stateRows" :key="row[0]">
                  <td>{{ row[0] }}</td>
                  <td class="number">{{ Number(row[1]) || 0 }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="section">
          <div class="section-header">
            <div class="section-title">在线会话</div>
            <div class="muted">按最近心跳倒序</div>
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
              <tbody>
                <tr v-if="!snapshot.sessions || snapshot.sessions.length === 0">
                  <td class="empty" colspan="8">No active game sessions.</td>
                </tr>
                <tr v-for="session in snapshot.sessions" :key="session.clientId">
                  <td><code :title="session.clientId || ''">{{ maskIdentifier(session.clientId || session.deviceId || 'unknown') }}</code></td>
                  <td>{{ session.playerName || '-' }}</td>
                  <td>{{ session.idType || 'unknown' }}</td>
                  <td>{{ session.state || 'unknown' }}</td>
                  <td>{{ session.appVersion || '-' }}</td>
                  <td>{{ formatDateTime(session.firstSeenAt) }}</td>
                  <td>{{ formatDateTime(session.lastSeenAt) }}<br><span class="muted">{{ formatAge(session.ageSeconds) }}</span></td>
                  <td>{{ Number(session.expiresInSeconds) || 0 }}s</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </main>
    `
  }).mount('#app');

  function buildChart(buckets) {
    const samples = (Array.isArray(buckets) ? buckets : [])
      .filter((bucket) => bucket && bucket.hasSnapshot !== false)
      .map((bucket) => ({
        at: bucket.bucketStart,
        online: Math.max(0, Number(bucket.online) || 0)
      }));
    if (samples.length === 0) {
      return {
        hasSamples: false,
        points: '',
        areaPath: '',
        gridLines: [],
        firstLabel: '-',
        lastLabel: '-',
        maxOnline: 0
      };
    }

    const maxOnline = Math.max(1, ...samples.map((item) => item.online));
    const x0 = 40;
    const y0 = 202;
    const width = 660;
    const height = 176;
    const denominator = Math.max(1, samples.length - 1);
    const points = samples.map((item, index) => {
      const x = x0 + (width * index / denominator);
      const y = y0 - (height * item.online / maxOnline);
      return {
        x,
        y,
        text: x.toFixed(1) + ',' + y.toFixed(1)
      };
    });
    const polyline = points.map((point) => point.text).join(' ');
    const areaPath = 'M ' + x0 + ' ' + y0 + ' L ' +
      points.map((point) => point.text).join(' L ') +
      ' L ' + (x0 + width) + ' ' + y0 + ' Z';

    return {
      hasSamples: true,
      points: polyline,
      areaPath,
      gridLines: [0, 1, 2, 3, 4].map((index) => ({
        y: 26 + (176 * index / 4)
      })),
      firstLabel: formatShortDateTime(samples[0].at),
      lastLabel: formatShortDateTime(samples[samples.length - 1].at),
      maxOnline
    };
  }
}());
