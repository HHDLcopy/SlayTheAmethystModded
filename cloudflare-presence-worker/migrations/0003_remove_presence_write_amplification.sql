DROP INDEX IF EXISTS idx_presence_sessions_last_seen_at_ms;
DROP INDEX IF EXISTS idx_presence_heartbeats_at_ms;
DROP INDEX IF EXISTS idx_presence_heartbeats_client_at_ms;

DROP TABLE IF EXISTS presence_heartbeats;
DROP TABLE IF EXISTS presence_devices;
