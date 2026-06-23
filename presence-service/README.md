# SlayTheAmethyst Presence Service

Standalone presence service for game-process online status. It replaces the old
Tencent SCF -> Cloudflare Worker/D1 chain with:

```text
Android app -> Fastify WebSocket -> SQLite3
```

The Android client keeps one WebSocket connection open, sends a full presence
frame when the connection opens or stable metadata changes, then sends minimal
heartbeat frames every 30 seconds by default. The Vue3 panel also uses WebSocket
server push for sessions and stats, so it no longer polls the HTTP endpoints.

## Features

- Public game presence WebSocket: `GET /api/presence/ws`
- Compatibility HTTP heartbeat: `POST /api/presence/heartbeat`
- Public online summary: `GET /api/presence/summary`
- Public online count alias: `GET /api/presence/online-count`
- Protected session list: `GET /api/presence/sessions?token=...`
- Protected hourly stats: `GET /api/presence/stats?token=...&bucket_seconds=3600&window_seconds=604800`
- Vue3 panel: `GET /presence`
- Panel WebSocket: `GET /api/presence/panel/ws?token=...`
- Cloud-control config: `GET /cloud-control.json`

## Run

```powershell
cd presence-service
npm install
$env:PRESENCE_PANEL_TOKEN = "change-me"
$env:PUBLIC_BASE_URL = "https://presence.example.com"
npm start
```

Open:

```text
http://localhost:8787/presence?token=change-me
```

## Configuration

```text
HOST=0.0.0.0
PORT=3001
PUBLIC_BASE_URL=https://presence.example.com
PRESENCE_DB_PATH=./data/presence.sqlite
PRESENCE_HEARTBEAT_INTERVAL_SECONDS=30
PRESENCE_OFFLINE_TIMEOUT_SECONDS=90
QQ_GROUP_NUMBER=1029305387
PRESENCE_PANEL_TOKEN=change-me
LOG_LEVEL=info
```

`PUBLIC_BASE_URL` is used to emit absolute URLs in `/cloud-control.json`.
Behind a reverse proxy, configure it to the public HTTPS origin so Android gets
a `wss://.../api/presence/ws` URL.

## Docker

Build locally:

```powershell
docker build -t ghcr.io/modinmobilests/slaytheamethyst-presence-service:latest .
```

Run with Docker Compose from the repository root:

```powershell
docker compose up -d presence-service
```

On a server that only has `docker-compose.yaml`, Compose pulls the published
GHCR image directly and does not need the `presence-service/` source directory.

The compose file exposes the service on:

```text
http://localhost:3001/presence?token=change-me
```

For production, set `PUBLIC_BASE_URL` to the public HTTPS origin and replace
`PRESENCE_PANEL_TOKEN`.

## Cloud-Control Payload

`GET /cloud-control.json` returns the compact WebSocket heartbeat settings and
the official QQ group used by launcher entry points:

```json
{
  "heartbeat": {
    "intervalSeconds": 30,
    "wsUrl": "wss://presence.example.com/api/presence/ws"
  },
  "qqGroup": {
    "number": "1029305387"
  }
}
```

The HTTP heartbeat endpoint remains available only for compatibility; new app
builds read `heartbeat.wsUrl`, report presence over WebSocket, and use
`qqGroup.number` when opening or displaying the official QQ group.

## WebSocket Messages

App -> server full presence frame, sent on WebSocket connect and whenever
metadata changes:

```json
{
  "type": "presence",
  "client_id": "android:...",
  "device_id": "...",
  "id_type": "android_id_sha256",
  "state": "game",
  "player_name": "Player",
  "app_version": "1.4.8",
  "device_model": "Google Pixel 8",
  "android_version": "Android 15 (SDK 35)",
  "sent_at": 1760000000000
}
```

App -> server minimal heartbeat frame, sent while the WebSocket connection is
already established and metadata is unchanged:

```json
{
  "type": "presence",
  "client_id": "android:...",
  "state": "game",
  "sent_at": 1760000000000
}
```

Minimal heartbeat frames update `state` and the latest heartbeat timestamp.
Missing metadata fields keep their previous stored values so the panel continues
to show player name, app version, model, and Android version from the full
presence frame.

Server -> app:

```json
{
  "type": "presence_ack",
  "ok": true,
  "online": 1,
  "totalOnlineUsers": 1,
  "heartbeatIntervalSeconds": 30,
  "offlineTimeoutSeconds": 90,
  "storageBackend": "sqlite3"
}
```

Panel messages use `type: "snapshot"` and `type: "stats"` with payloads matching
the compatibility HTTP JSON responses. Send `type: "refresh_stats"` with
`windowSeconds` to switch the trend window; supported panel choices are 24
hours, 3 days, 7 days, 14 days, and 30 days.

The panel pie chart can switch between current online sessions and historical
unique devices. Historical distribution is aggregated from all rows in
`presence_sessions`, while online distribution is calculated from active
sessions in the latest panel snapshot.
