CREATE TABLE IF NOT EXISTS presence_hourly_snapshots (
  snapshot_hour_ms INTEGER PRIMARY KEY,
  online INTEGER NOT NULL DEFAULT 0,
  by_state_json TEXT NOT NULL DEFAULT '{}',
  total_devices INTEGER NOT NULL DEFAULT 0,
  created_at_ms INTEGER NOT NULL,
  updated_at_ms INTEGER NOT NULL
);

