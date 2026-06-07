CREATE TABLE IF NOT EXISTS presence_sessions (
  client_id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL DEFAULT '',
  id_type TEXT NOT NULL DEFAULT '',
  state TEXT NOT NULL DEFAULT 'game',
  player_name TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '',
  first_seen_at_ms INTEGER NOT NULL,
  last_seen_at_ms INTEGER NOT NULL
);
