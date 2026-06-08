# SlayTheAmethyst Presence Service

Standalone presence service for game-process online status. It replaces the old
Tencent SCF -> Cloudflare Worker/D1 chain with:

```text
Android app -> Fastify WebSocket -> SQLite3
```

The Android client keeps one WebSocket connection open, sends presence status
every 30 seconds by default, and reconnects automatically. The Vue3 panel also
uses WebSocket server push for sessions and stats, so it no longer polls the
HTTP endpoints.

## Features

- Public game presence WebSocket: `GET /api/presence/ws`
- Compatibility HTTP heartbeat: `POST /api/presence/heartbeat`
- Public online summary: `GET /api/presence/summary`
- Public online count alias: `GET /api/presence/online-count`
- Protected session list: `GET /api/presence/sessions?token=...`
- Protected one-week hourly stats: `GET /api/presence/stats?token=...&bucket_seconds=3600`
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

`GET /cloud-control.json` returns both old HTTP fields and the new WebSocket
fields:

```json
{
  "heartbeatIntervalSeconds": 30,
  "heartbeatRequestApiUrl": "https://presence.example.com/api/presence/heartbeat",
  "heartbeatWsUrl": "wss://presence.example.com/api/presence/ws",
  "presenceHeartbeatWsUrl": "wss://presence.example.com/api/presence/ws",
  "heartbeat": {
    "intervalSeconds": 30,
    "apiUrl": "https://presence.example.com/api/presence/heartbeat",
    "wsUrl": "wss://presence.example.com/api/presence/ws"
  }
}
```

## WebSocket Messages

App -> server:

```json
{
  "type": "presence",
  "client_id": "android:...",
  "device_id": "...",
  "id_type": "android_id_sha256",
  "state": "game",
  "player_name": "Player",
  "app_version": "1.4.8",
  "sent_at": 1760000000000
}
```

Server -> app:

```json
{
  "type": "presence_ack",
  "ok": true,
  "online": 1,
  "heartbeatIntervalSeconds": 30,
  "offlineTimeoutSeconds": 90,
  "storageBackend": "sqlite3"
}
```

Panel messages use `type: "snapshot"` and `type: "stats"` with payloads matching
the compatibility HTTP JSON responses.
