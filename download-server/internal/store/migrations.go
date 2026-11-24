package store

var schemaMigrations = []string{
	`CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY,
		applied_at INTEGER NOT NULL
	);`,
	`CREATE TABLE IF NOT EXISTS sources (
		id TEXT PRIMARY KEY,
		account_id TEXT NOT NULL,
		remote_root_id TEXT NOT NULL,
		remote_root_path TEXT NOT NULL,
		local_alias TEXT NOT NULL UNIQUE,
		initial_mode TEXT NOT NULL,
		baseline_established INTEGER NOT NULL DEFAULT 0,
		enabled INTEGER NOT NULL DEFAULT 1,
		scan_interval_sec INTEGER NOT NULL,
		last_scan_generation INTEGER NOT NULL DEFAULT 0,
		last_scan_at INTEGER NOT NULL DEFAULT 0,
		last_scan_status TEXT NOT NULL DEFAULT '',
		last_scan_error TEXT NOT NULL DEFAULT '',
		consecutive_failures INTEGER NOT NULL DEFAULT 0,
		next_scan_at INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	);
	CREATE TABLE IF NOT EXISTS scan_runs (
		id TEXT PRIMARY KEY,
		source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
		generation INTEGER NOT NULL,
		status TEXT NOT NULL,
		error_message TEXT NOT NULL DEFAULT '',
		entries_count INTEGER NOT NULL DEFAULT 0,
		started_at INTEGER NOT NULL,
		finished_at INTEGER NOT NULL DEFAULT 0
	);
	CREATE INDEX IF NOT EXISTS idx_scan_runs_source ON scan_runs(source_id, generation DESC);
	CREATE TABLE IF NOT EXISTS scan_entries (
		scan_run_id TEXT NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
		source_id TEXT NOT NULL,
		remote_id TEXT NOT NULL,
		parent_remote_id TEXT NOT NULL,
		relative_path TEXT NOT NULL,
		name TEXT NOT NULL,
		is_dir INTEGER NOT NULL,
		size INTEGER NOT NULL,
		gcid TEXT NOT NULL,
		version_key TEXT NOT NULL,
		strong_version INTEGER NOT NULL,
		remote_modified_at INTEGER NOT NULL,
		PRIMARY KEY (scan_run_id, remote_id)
	);
	CREATE TABLE IF NOT EXISTS remote_objects (
		id TEXT PRIMARY KEY,
		source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
		remote_id TEXT NOT NULL,
		parent_remote_id TEXT NOT NULL,
		relative_path TEXT NOT NULL,
		name TEXT NOT NULL,
		is_dir INTEGER NOT NULL,
		size INTEGER NOT NULL,
		gcid TEXT NOT NULL,
		version_key TEXT NOT NULL,
		baseline_version_key TEXT NOT NULL DEFAULT '',
		strong_version INTEGER NOT NULL,
		remote_modified_at INTEGER NOT NULL,
		first_seen_at INTEGER NOT NULL,
		last_seen_at INTEGER NOT NULL,
		last_seen_generation INTEGER NOT NULL,
		miss_count INTEGER NOT NULL DEFAULT 0,
		remote_state TEXT NOT NULL,
		missing_since INTEGER NOT NULL DEFAULT 0,
		UNIQUE (source_id, remote_id)
	);
	CREATE INDEX IF NOT EXISTS idx_remote_objects_source_state ON remote_objects(source_id, remote_state);
	CREATE TABLE IF NOT EXISTS download_tasks (
		id TEXT PRIMARY KEY,
		source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
		remote_id TEXT NOT NULL,
		version_key TEXT NOT NULL,
		gcid TEXT NOT NULL,
		strong_version INTEGER NOT NULL,
		remote_path TEXT NOT NULL,
		target_rel_path TEXT NOT NULL,
		state TEXT NOT NULL,
		desired_action TEXT NOT NULL DEFAULT '',
		attempt INTEGER NOT NULL DEFAULT 0,
		max_attempts INTEGER NOT NULL DEFAULT 5,
		priority INTEGER NOT NULL DEFAULT 0,
		bytes_total INTEGER NOT NULL DEFAULT 0,
		bytes_done INTEGER NOT NULL DEFAULT 0,
		staging_rel_path TEXT NOT NULL,
		commit_sha256 TEXT NOT NULL DEFAULT '',
		error_code TEXT NOT NULL DEFAULT '',
		error_message TEXT NOT NULL DEFAULT '',
		next_retry_at INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL,
		started_at INTEGER NOT NULL DEFAULT 0,
		finished_at INTEGER NOT NULL DEFAULT 0,
		UNIQUE (source_id, remote_id, version_key)
	);
	CREATE INDEX IF NOT EXISTS idx_download_tasks_state ON download_tasks(state, priority DESC, created_at ASC);
	CREATE INDEX IF NOT EXISTS idx_download_tasks_source ON download_tasks(source_id, remote_id);
	CREATE TABLE IF NOT EXISTS deliveries (
		id TEXT PRIMARY KEY,
		source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
		remote_id TEXT NOT NULL,
		version_key TEXT NOT NULL,
		task_id TEXT NOT NULL REFERENCES download_tasks(id) ON DELETE CASCADE,
		local_rel_path TEXT NOT NULL UNIQUE,
		size INTEGER NOT NULL,
		local_sha256 TEXT NOT NULL,
		remote_gcid TEXT NOT NULL,
		verified_remote_hash INTEGER NOT NULL DEFAULT 0,
		delivered_at INTEGER NOT NULL
	);
	CREATE INDEX IF NOT EXISTS idx_deliveries_object ON deliveries(source_id, remote_id, version_key);
	CREATE TABLE IF NOT EXISTS admin_sessions (
		token_hash TEXT PRIMARY KEY,
		username TEXT NOT NULL,
		csrf_token TEXT NOT NULL,
		expires_at INTEGER NOT NULL,
		created_at INTEGER NOT NULL
	);`,
	`CREATE TABLE IF NOT EXISTS app_settings (
		key TEXT PRIMARY KEY,
		value TEXT NOT NULL,
		updated_at INTEGER NOT NULL
	);`,
	`CREATE TABLE IF NOT EXISTS admin_users (
		id INTEGER PRIMARY KEY CHECK (id = 1),
		username TEXT NOT NULL,
		password_hash TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	);`,
	`ALTER TABLE sources ADD COLUMN min_file_size_bytes INTEGER NOT NULL DEFAULT 0;`,
}
