(function () {
  'use strict';

  const {
    createApp,
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    reactive,
    ref,
    watch
  } = Vue;
  const { createVuetify } = Vuetify;
  const HOUR_SECONDS = 60 * 60;
  const PRESENCE_SERVICE_BASE_URL = normalizeServiceBaseUrl(
    window.PRESENCE_SERVICE_BASE_URL,
    'https://heartbeat.nas.apricityx.top:23163'
  );

  const STATS_WINDOW_ITEMS = [
    { title: '24小时', value: 24 * HOUR_SECONDS },
    { title: '3天', value: 3 * 24 * HOUR_SECONDS },
    { title: '7天', value: 7 * 24 * HOUR_SECONDS },
    { title: '14天', value: 14 * 24 * HOUR_SECONDS },
    { title: '30天', value: 30 * 24 * HOUR_SECONDS }
  ];
  const DEFAULT_STATS_WINDOW_SECONDS = 7 * 24 * HOUR_SECONDS;

  const METRIC_ITEMS = [
    {
      key: 'online',
      title: '当前在线',
      icon: 'mdi-account-multiple',
      color: 'primary',
      value(snapshot) {
        return String(Number(snapshot.online) || 0);
      },
      subtitle() {
        return '按最近心跳计算';
      }
    },
    {
      key: 'heartbeat',
      title: '心跳间隔',
      icon: 'mdi-heart-pulse',
      color: 'success',
      value(snapshot) {
        return (Number(snapshot.heartbeatIntervalSeconds) || 0) + 's';
      },
      subtitle() {
        return 'App WebSocket 上报';
      }
    },
    {
      key: 'totalOnline',
      title: '累计在线',
      icon: 'mdi-counter',
      color: 'warning',
      value(snapshot) {
        return String(getTotalOnlineUsers(snapshot));
      },
      subtitle() {
        return '历史唯一上报设备';
      }
    },
    {
      key: 'storage',
      title: '存储后端',
      icon: 'mdi-database',
      color: 'info',
      value(snapshot) {
        return snapshot.storageBackend || 'sqlite3';
      },
      subtitle(snapshot) {
        return snapshot.checkedAt ? formatDateTime(snapshot.checkedAt) : '等待快照';
      }
    }
  ];

  const SESSION_HEADERS = [
    { title: '设备', key: 'clientId', minWidth: 210 },
    { title: '玩家名', key: 'playerName', minWidth: 130 },
    { title: '机型', key: 'deviceModel', minWidth: 160 },
    { title: 'Android', key: 'androidVersion', minWidth: 130 },
    { title: 'ID 类型', key: 'idType', minWidth: 150 },
    { title: '状态', key: 'state', minWidth: 110 },
    { title: '版本', key: 'appVersion', minWidth: 100 },
    { title: '首次在线', key: 'firstSeenAt', minWidth: 170 },
    { title: '最近心跳', key: 'lastSeenAt', minWidth: 180 },
    { title: '剩余 TTL', key: 'expiresInSeconds', align: 'end', minWidth: 110 }
  ];

  function normalizeServiceBaseUrl(value, fallbackValue) {
    const rawValue = String(value || fallbackValue || '').trim();
    const normalized = rawValue.endsWith('/') ? rawValue.slice(0, -1) : rawValue;
    if (!normalized) {
      return '';
    }
    try {
      return new URL(normalized).origin;
    } catch (_error) {
      return new URL(fallbackValue).origin;
    }
  }

  function buildServiceUrl(pathname) {
    return new URL(pathname, PRESENCE_SERVICE_BASE_URL + '/');
  }

  function toWebSocketUrl(url) {
    return String(url || '').replace(/^https:/i, 'wss:').replace(/^http:/i, 'ws:');
  }

  function buildPanelWebSocketUrl(token, windowSeconds) {
    const url = buildServiceUrl('/api/presence/panel/ws');
    if (token) {
      url.searchParams.set('token', token);
    }
    url.searchParams.set('window_seconds', String(normalizeStatsWindowSeconds(windowSeconds)));
    return toWebSocketUrl(url.toString());
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

  function normalizeStatsWindowSeconds(value) {
    const parsed = Number(value) || DEFAULT_STATS_WINDOW_SECONDS;
    const matched = STATS_WINDOW_ITEMS.find((item) => item.value === parsed);
    return matched ? matched.value : DEFAULT_STATS_WINDOW_SECONDS;
  }

  function formatStatsWindowLabel(value) {
    const normalized = normalizeStatsWindowSeconds(value);
    const matched = STATS_WINDOW_ITEMS.find((item) => item.value === normalized);
    return matched ? matched.title : '7天';
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

  function getTotalOnlineUsers(snapshot) {
    const explicitValue = Number(snapshot && snapshot.totalOnlineUsers);
    if (Number.isFinite(explicitValue)) {
      return Math.max(0, explicitValue);
    }
    return Math.max(0, Number(snapshot && snapshot.totalDevices) || 0);
  }

  function buildChartOption(stats) {
    const buckets = Array.isArray(stats && stats.buckets) ? stats.buckets : [];
    const samples = buckets
      .filter((bucket) => bucket && bucket.hasSnapshot !== false)
      .map((bucket) => ({
        label: formatShortDateTime(bucket.bucketStart),
        online: Math.max(0, Number(bucket.online) || 0),
        totalOnlineUsers: getTotalOnlineUsers(bucket),
        recordedAt: bucket.recordedAt ? formatDateTime(bucket.recordedAt) : '-'
      }));

    return {
      color: ['#2563eb'],
      animationDuration: 220,
      tooltip: {
        trigger: 'axis',
        confine: true,
        formatter(params) {
          const item = params && params[0] && params[0].data;
          if (!item) {
            return '';
          }
          return [
            '<strong>' + item.label + '</strong>',
            '该时刻在线: ' + item.online,
            '累计在线: ' + item.totalOnlineUsers,
            '记录时间: ' + item.recordedAt
          ].join('<br>');
        }
      },
      grid: {
        top: 28,
        right: 18,
        bottom: 42,
        left: 42,
        containLabel: true
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: samples.map((item) => item.label),
        axisLabel: {
          hideOverlap: true,
          color: '#64748b'
        },
        axisLine: {
          lineStyle: {
            color: '#cbd5e1'
          }
        },
        axisTick: {
          show: false
        }
      },
      yAxis: {
        type: 'value',
        name: '该时刻在线人数',
        nameTextStyle: {
          color: '#64748b',
          fontSize: 12
        },
        minInterval: 1,
        axisLabel: {
          color: '#64748b'
        },
        splitLine: {
          lineStyle: {
            color: '#e2e8f0'
          }
        }
      },
      series: [
        {
          name: '该时刻在线人数',
          type: 'line',
          smooth: true,
          showSymbol: samples.length <= 36,
          symbolSize: 6,
          lineStyle: {
            width: 3
          },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: 'rgba(37, 99, 235, .22)' },
                { offset: 1, color: 'rgba(37, 99, 235, .02)' }
              ]
            }
          },
          data: samples.map((item) => ({
            ...item,
            value: item.online
          }))
        }
      ]
    };
  }

  createApp({
    setup() {
      const chartEl = ref(null);
      let chart = null;
      let resizeObserver = null;

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
        selectedStatsWindowSeconds: DEFAULT_STATS_WINDOW_SECONDS,
        lastError: ''
      });

      const hasToken = computed(() => state.token.trim().length > 0);
      const isConnected = computed(() => state.connectionStatus === 'connected');
      const isError = computed(() => state.connectionStatus === 'error');
      const isConnecting = computed(() => state.connectionStatus === 'connecting');
      const snapshot = computed(() => state.snapshot || {
        online: 0,
        byState: {},
        heartbeatIntervalSeconds: 0,
        offlineTimeoutSeconds: 0,
        checkedAt: '',
        storageBackend: 'sqlite3',
        totalDevices: 0,
        totalOnlineUsers: 0,
        sessions: []
      });
      const stats = computed(() => state.stats || {
        peakOnline: 0,
        currentOnline: 0,
        totalOnlineUsers: 0,
        windowSeconds: state.selectedStatsWindowSeconds,
        snapshotCount: 0,
        buckets: [],
        since: '',
        until: ''
      });
      const sessions = computed(() => Array.isArray(snapshot.value.sessions)
        ? snapshot.value.sessions
        : []);
      const metricItems = computed(() => METRIC_ITEMS.map((item) => ({
        key: item.key,
        title: item.title,
        icon: item.icon,
        color: item.color,
        value: item.value(snapshot.value),
        subtitle: item.subtitle(snapshot.value)
      })));
      const hasStatsSamples = computed(() => (stats.value.buckets || [])
        .some((bucket) => bucket && bucket.hasSnapshot !== false));
      const selectedStatsWindowLabel = computed(() => formatStatsWindowLabel(state.selectedStatsWindowSeconds));
      const connectionColor = computed(() => {
        if (isConnected.value) {
          return 'success';
        }
        if (isError.value) {
          return 'error';
        }
        return 'warning';
      });
      const connectionIcon = computed(() => {
        if (isConnected.value) {
          return 'mdi-lan-connect';
        }
        if (isError.value) {
          return 'mdi-lan-disconnect';
        }
        return 'mdi-lan-pending';
      });

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
        const ws = new WebSocket(buildPanelWebSocketUrl(state.token, state.selectedStatsWindowSeconds));
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
          if (state.stats && state.stats.windowSeconds) {
            state.selectedStatsWindowSeconds = normalizeStatsWindowSeconds(state.stats.windowSeconds);
          }
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

      function sendStatsRefresh() {
        const ws = state.ws;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          connect();
          return;
        }
        ws.send(JSON.stringify({
          type: 'refresh_stats',
          windowSeconds: state.selectedStatsWindowSeconds
        }));
      }

      function refreshAll() {
        send('refresh');
        sendStatsRefresh();
      }

      function selectStatsWindow(windowSeconds) {
        state.selectedStatsWindowSeconds = normalizeStatsWindowSeconds(windowSeconds);
        sendStatsRefresh();
      }

      function tableItem(item) {
        return item && item.raw ? item.raw : (item || {});
      }

      function ensureChart() {
        if (!hasStatsSamples.value || !chartEl.value || typeof echarts === 'undefined') {
          return;
        }
        if (!chart) {
          chart = echarts.init(chartEl.value, null, {
            renderer: 'canvas'
          });
        }
        chart.setOption(buildChartOption(stats.value), true);
      }

      function resizeChart() {
        if (chart) {
          chart.resize();
        }
      }

      watch(stats, () => {
        nextTick(ensureChart);
      }, { deep: true });

      watch(() => state.selectedStatsWindowSeconds, () => {
        nextTick(ensureChart);
      });

      onMounted(() => {
        if (hasToken.value) {
          connect();
        }
        nextTick(ensureChart);
        if (window.ResizeObserver && chartEl.value) {
          resizeObserver = new ResizeObserver(resizeChart);
          resizeObserver.observe(chartEl.value);
        }
        window.addEventListener('resize', resizeChart);
      });

      onBeforeUnmount(() => {
        disconnect(true);
        window.removeEventListener('resize', resizeChart);
        if (resizeObserver) {
          resizeObserver.disconnect();
          resizeObserver = null;
        }
        if (chart) {
          chart.dispose();
          chart = null;
        }
      });

      return {
        chartEl,
        state,
        PRESENCE_SERVICE_BASE_URL,
        hasToken,
        isConnected,
        isConnecting,
        snapshot,
        stats,
        sessions,
        metricItems,
        hasStatsSamples,
        selectedStatsWindowLabel,
        connectionColor,
        connectionIcon,
        STATS_WINDOW_ITEMS,
        SESSION_HEADERS,
        login,
        refreshAll,
        selectStatsWindow,
        tableItem,
        formatAge,
        formatDateTime,
        formatShortDateTime,
        maskIdentifier
      };
    },
    template: `
      <v-app>
        <v-main class="presence-shell">
          <v-container v-if="!hasToken" class="login-container" fluid>
            <v-card class="login-card" elevation="2">
              <v-card-title class="text-h5">在线情况面板</v-card-title>
              <v-card-text>
                <v-form @submit.prevent="login">
                  <v-text-field
                    v-model="state.inputToken"
                    label="访问令牌"
                    type="password"
                    autocomplete="current-password"
                    variant="outlined"
                    density="comfortable"
                    autofocus
                    hide-details
                  ></v-text-field>
                  <v-btn
                    class="mt-4"
                    color="primary"
                    type="submit"
                    block
                    size="large"
                    prepend-icon="mdi-login"
                  >
                    进入
                  </v-btn>
                </v-form>
              </v-card-text>
            </v-card>
          </v-container>

          <v-container v-else class="page-container" fluid>
            <v-toolbar class="top-toolbar" color="surface" rounded="lg" density="comfortable">
              <template #prepend>
                <v-icon icon="mdi-monitor-dashboard" size="28"></v-icon>
              </template>
              <v-toolbar-title>
                <div class="title-line">在线情况面板</div>
                <div class="subtitle-line">SlayTheAmethyst · {{ PRESENCE_SERVICE_BASE_URL }} · {{ snapshot.checkedAt || '-' }}</div>
              </v-toolbar-title>
              <v-spacer></v-spacer>
              <v-chip
                class="mr-2"
                :color="connectionColor"
                :prepend-icon="connectionIcon"
                variant="tonal"
              >
                {{ state.connectionMessage }}
              </v-chip>
              <v-btn
                color="primary"
                variant="flat"
                prepend-icon="mdi-refresh"
                :loading="isConnecting"
                @click="refreshAll"
              >
                刷新
              </v-btn>
            </v-toolbar>

            <v-row class="mt-4" dense>
              <v-col v-for="item in metricItems" :key="item.key" cols="12" sm="6" lg="3">
                <v-card class="metric-card" elevation="1">
                  <v-card-text>
                    <div class="metric-heading">
                      <v-avatar :color="item.color" variant="tonal" size="42">
                        <v-icon :icon="item.icon"></v-icon>
                      </v-avatar>
                      <span>{{ item.title }}</span>
                    </div>
                    <div class="metric-value">{{ item.value }}</div>
                    <div class="metric-subtitle">{{ item.subtitle }}</div>
                  </v-card-text>
                </v-card>
              </v-col>
            </v-row>

            <v-card class="mt-4" elevation="1">
              <v-card-title class="panel-title">
                <div>
                  <div>{{ selectedStatsWindowLabel }}在线趋势</div>
                  <div class="panel-subtitle">{{ formatShortDateTime(stats.since) }} - {{ formatShortDateTime(stats.until) }}</div>
                </div>
                <v-spacer></v-spacer>
                <v-btn-toggle
                  v-model="state.selectedStatsWindowSeconds"
                  class="stats-window-toggle"
                  color="primary"
                  density="comfortable"
                  divided
                  mandatory
                  variant="outlined"
                  @update:model-value="selectStatsWindow"
                >
                  <v-btn
                    v-for="item in STATS_WINDOW_ITEMS"
                    :key="item.value"
                    :value="item.value"
                    size="small"
                  >
                    {{ item.title }}
                  </v-btn>
                </v-btn-toggle>
                <v-chip color="primary" variant="tonal">峰值 {{ Number(stats.peakOnline) || 0 }}</v-chip>
                <v-chip color="info" variant="tonal">样本 {{ Number(stats.snapshotCount) || 0 }}/{{ (stats.buckets || []).length }}</v-chip>
              </v-card-title>
              <v-card-text>
                <div v-show="hasStatsSamples" ref="chartEl" class="presence-chart"></div>
                <div v-if="!hasStatsSamples" class="empty-state">
                  <v-icon icon="mdi-chart-line" size="44" color="primary"></v-icon>
                  <div class="empty-title">暂无趋势样本</div>
                  <div class="empty-text">打开面板后当前小时快照会自动写入，历史趋势按小时累积。</div>
                </div>
              </v-card-text>
            </v-card>

            <v-row class="mt-4" dense>
              <v-col cols="12">
                <v-card elevation="1">
                  <v-card-title class="panel-title">
                    <div>
                      <div>在线会话</div>
                      <div class="panel-subtitle">按最近心跳倒序</div>
                    </div>
                  </v-card-title>
                  <v-data-table
                    :headers="SESSION_HEADERS"
                    :items="sessions"
                    density="comfortable"
                    fixed-header
                    height="520"
                    item-value="clientId"
                    no-data-text="No active game sessions."
                    :items-per-page="25"
                  >
                    <template #item.clientId="{ item }">
                      <code class="identifier" :title="tableItem(item).clientId || ''">
                        {{ maskIdentifier(tableItem(item).clientId || tableItem(item).deviceId || 'unknown') }}
                      </code>
                    </template>
                    <template #item.playerName="{ item }">
                      {{ tableItem(item).playerName || '-' }}
                    </template>
                    <template #item.deviceModel="{ item }">
                      <span :title="tableItem(item).deviceModel || ''">
                        {{ tableItem(item).deviceModel || '-' }}
                      </span>
                    </template>
                    <template #item.androidVersion="{ item }">
                      {{ tableItem(item).androidVersion || '-' }}
                    </template>
                    <template #item.idType="{ item }">
                      {{ tableItem(item).idType || 'unknown' }}
                    </template>
                    <template #item.state="{ item }">
                      <v-chip color="primary" variant="tonal" size="small">{{ tableItem(item).state || 'unknown' }}</v-chip>
                    </template>
                    <template #item.appVersion="{ item }">
                      {{ tableItem(item).appVersion || '-' }}
                    </template>
                    <template #item.firstSeenAt="{ item }">
                      {{ formatDateTime(tableItem(item).firstSeenAt) }}
                    </template>
                    <template #item.lastSeenAt="{ item }">
                      <div>{{ formatDateTime(tableItem(item).lastSeenAt) }}</div>
                      <div class="table-subtitle">{{ formatAge(tableItem(item).ageSeconds) }}</div>
                    </template>
                    <template #item.expiresInSeconds="{ item }">
                      {{ Number(tableItem(item).expiresInSeconds) || 0 }}s
                    </template>
                  </v-data-table>
                </v-card>
              </v-col>
            </v-row>
          </v-container>
        </v-main>
      </v-app>
    `
  })
    .use(createVuetify({
      icons: {
        defaultSet: 'mdi'
      },
      theme: {
        defaultTheme: window.matchMedia &&
          window.matchMedia('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light',
        themes: {
          light: {
            colors: {
              background: '#f6f8fb',
              surface: '#ffffff',
              primary: '#2563eb',
              success: '#0f9f6e',
              warning: '#b7791f',
              info: '#0284c7'
            }
          },
          dark: {
            colors: {
              background: '#101418',
              surface: '#171d24',
              primary: '#7aa7ff',
              success: '#61d394',
              warning: '#e5b95f',
              info: '#62c4f3'
            }
          }
        }
      },
      defaults: {
        VCard: {
          rounded: 'lg'
        },
        VBtn: {
          rounded: 'md'
        },
        VDataTable: {
          hover: true
        }
      }
    }))
    .mount('#app');
}());
