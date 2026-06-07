# Cloudflare Presence Storage Worker

This Worker stores game presence data in Cloudflare D1. Tencent SCF remains the public API and calls this Worker as an internal storage service.

## Endpoints

All internal endpoints require:

```text
Authorization: Bearer <PRESENCE_STORAGE_SECRET>
```

```text
POST /internal/presence/heartbeat
GET  /internal/presence/summary
GET  /internal/presence/sessions
GET  /internal/presence/stats?bucket_seconds=3600
GET  /healthz
```

`/healthz` does not require the secret.

## Data model

```text
presence_sessions          Current latest heartbeat per client_id
presence_hourly_snapshots  Hourly online-count snapshots used for one-week charts
```

`presence_sessions` is never TTL-deleted; `first_seen_at_ms` preserves historical unique-device counting and `last_seen_at_ms` drives online checks. There is intentionally no `last_seen_at_ms` index because each heartbeat updates that column and the index would double D1 write usage. `presence_hourly_snapshots` is pruned to the latest week.

## Deploy

Install dependencies:

```powershell
cd cloudflare-presence-worker
npm install
```

Login to Cloudflare:

```powershell
npx wrangler login
```

Create the D1 database:

```powershell
npm run d1:create
```

Copy the returned `database_id` into `wrangler.toml`:

```toml
[[d1_databases]]
binding = "DB"
database_name = "sts-presence"
database_id = "..."
```

Create the internal secret:

```powershell
npx wrangler secret put PRESENCE_STORAGE_SECRET
```

Apply the remote D1 migration:

```powershell
npm run d1:migrate:remote
```

The hourly snapshot migration creates `presence_hourly_snapshots`. The third migration removes the older heartbeat-history table and `last_seen_at_ms` index to reduce write amplification. Apply migrations before deploying Worker code that reads the weekly chart.

Deploy the Worker:

```powershell
npm run deploy
```

The deployed Worker uses a Cron Trigger (`0 * * * *`) to write one online-count snapshot every hour. The stats endpoint also refreshes the current hour at most once every five minutes when the panel is open.

After deployment, set these Tencent SCF environment variables:

```text
PRESENCE_STORAGE_URL=https://sts.presence.mctown.online
PRESENCE_STORAGE_SECRET=<same value as the Worker secret>
PRESENCE_STORAGE_TIMEOUT_MS=3000
```

Keep these existing SCF variables as the Tencent-side business configuration:

```text
PRESENCE_HEARTBEAT_INTERVAL_SECONDS=600
PRESENCE_OFFLINE_TIMEOUT_SECONDS=1500
PRESENCE_PANEL_TOKEN=...
```

Then redeploy Tencent SCF.

The Worker also coalesces duplicate heartbeats server-side: it only updates `presence_sessions.last_seen_at_ms` when the previous stored heartbeat is at least roughly one configured heartbeat interval old, with a 60-second grace window. That protects D1 if older clients still send 240-second heartbeats.

The Worker treats its own `PRESENCE_HEARTBEAT_INTERVAL_SECONDS` and `PRESENCE_OFFLINE_TIMEOUT_SECONDS` variables as authoritative. Tencent SCF still forwards these values for compatibility, but Worker-side values win so Cloudflare deployment can enforce the quota-safe policy immediately.

## Local checks

```powershell
npm run check
npm run d1:migrate:local
npm run dev
```
