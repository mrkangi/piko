package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/nihildigit/piko/download-server/internal/localfs"
	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
}

func Open(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	dbPath := filepath.Join(dataDir, "download-server.db")
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	for _, pragma := range []string{
		"PRAGMA journal_mode=WAL;",
		"PRAGMA synchronous=FULL;",
		"PRAGMA busy_timeout=5000;",
		"PRAGMA foreign_keys=ON;",
	} {
		if _, err := db.Exec(pragma); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("apply %s: %w", pragma, err)
		}
	}

	store := &Store{db: db}
	if err := store.migrate(); err != nil {
		_ = db.Close()
		return nil, err
	}
	return store, nil
}

func (store *Store) Close() error {
	if store == nil || store.db == nil {
		return nil
	}
	return store.db.Close()
}

func (store *Store) Ping(ctx context.Context) error {
	return store.db.PingContext(ctx)
}

func (store *Store) migrate() error {
	if _, err := store.db.Exec(schemaMigrations[0]); err != nil {
		return fmt.Errorf("init schema_migrations: %w", err)
	}
	var current int
	if err := store.db.QueryRow(`SELECT COALESCE(MAX(version), 0) FROM schema_migrations`).Scan(&current); err != nil {
		return fmt.Errorf("query schema version: %w", err)
	}
	for idx := 1; idx < len(schemaMigrations); idx++ {
		version := idx
		if version <= current {
			continue
		}
		tx, err := store.db.Begin()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(schemaMigrations[idx]); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("apply migration %d: %w", version, err)
		}
		if _, err := tx.Exec(`INSERT INTO schema_migrations(version, applied_at) VALUES(?, ?)`, version, time.Now().Unix()); err != nil {
			_ = tx.Rollback()
			return fmt.Errorf("record migration %d: %w", version, err)
		}
		if err := tx.Commit(); err != nil {
			return fmt.Errorf("commit migration %d: %w", version, err)
		}
	}
	return nil
}

func (store *Store) beginImmediate(ctx context.Context) (*sql.Tx, error) {
	tx, err := store.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	if _, err := tx.ExecContext(ctx, "ROLLBACK; BEGIN IMMEDIATE;"); err != nil {
		_ = tx.Rollback()
		return nil, err
	}
	return tx, nil
}

func newID(prefix string) string {
	var raw [8]byte
	_, _ = rand.Read(raw[:])
	return prefix + "_" + hex.EncodeToString(raw[:])
}

func (store *Store) AbortStaleScanRuns(ctx context.Context) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `DELETE FROM scan_entries WHERE scan_run_id IN (SELECT id FROM scan_runs WHERE status = 'RUNNING')`); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE scan_runs SET status = 'ABORTED', error_message = 'interrupted by process restart', finished_at = ? WHERE status = 'RUNNING'`, now); err != nil {
		return err
	}
	return tx.Commit()
}

func (store *Store) CreateSource(ctx context.Context, src Source) (Source, error) {
	if src.ID == "" {
		src.ID = newID("src")
	}
	now := time.Now().Unix()
	src.CreatedAt = now
	src.UpdatedAt = now
	if src.NextScanAt == 0 && src.Enabled {
		src.NextScanAt = now
	}
	_, err := store.db.ExecContext(ctx, `
		INSERT INTO sources(
			id, account_id, remote_root_id, remote_root_path, local_alias,
			initial_mode, baseline_established, enabled, scan_interval_sec, min_file_size_bytes,
			last_scan_generation, last_scan_at, last_scan_status, last_scan_error,
			consecutive_failures, next_scan_at, created_at, updated_at
		) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		src.ID, src.AccountID, src.RemoteRootID, src.RemoteRootPath, src.LocalAlias,
		src.InitialMode, boolToInt(src.BaselineEstablished), boolToInt(src.Enabled), src.ScanIntervalSec, src.MinFileSizeBytes,
		src.LastScanGeneration, src.LastScanAt, src.LastScanStatus, src.LastScanError,
		src.ConsecutiveFailures, src.NextScanAt, src.CreatedAt, src.UpdatedAt,
	)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return Source{}, ErrDuplicateAlias
		}
		return Source{}, err
	}
	return src, nil
}

func (store *Store) UpdateSource(ctx context.Context, id string, enabled *bool, scanIntervalSec *int, minFileSizeBytes *int64, triggerNow bool) (Source, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return Source{}, err
	}
	defer tx.Rollback()

	src, err := getSourceTx(ctx, tx, id)
	if err != nil {
		return Source{}, err
	}
	now := time.Now().Unix()
	if enabled != nil {
		src.Enabled = *enabled
		if src.Enabled && src.NextScanAt == 0 {
			src.NextScanAt = now
		}
	}
	if scanIntervalSec != nil && *scanIntervalSec >= 10 {
		src.ScanIntervalSec = *scanIntervalSec
	}
	if minFileSizeBytes != nil {
		if *minFileSizeBytes < 0 {
			return Source{}, ErrConflict
		}
		src.MinFileSizeBytes = *minFileSizeBytes
	}
	if triggerNow {
		src.NextScanAt = now
	}
	src.UpdatedAt = now
	if _, err := tx.ExecContext(ctx, `
		UPDATE sources SET enabled = ?, scan_interval_sec = ?, min_file_size_bytes = ?, next_scan_at = ?, updated_at = ?
		WHERE id = ?`,
		boolToInt(src.Enabled), src.ScanIntervalSec, src.MinFileSizeBytes, src.NextScanAt, src.UpdatedAt, src.ID,
	); err != nil {
		return Source{}, err
	}
	if err := tx.Commit(); err != nil {
		return Source{}, err
	}
	return src, nil
}

func (store *Store) GetSource(ctx context.Context, id string) (Source, error) {
	return getSourceQuery(ctx, store.db, id)
}

func (store *Store) ListSources(ctx context.Context) ([]Source, error) {
	rows, err := store.db.QueryContext(ctx, `
		SELECT id, account_id, remote_root_id, remote_root_path, local_alias,
			initial_mode, baseline_established, enabled, scan_interval_sec, min_file_size_bytes,
			last_scan_generation, last_scan_at, last_scan_status, last_scan_error,
			consecutive_failures, next_scan_at, created_at, updated_at
		FROM sources ORDER BY created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Source
	for rows.Next() {
		src, err := scanSourceRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, src)
	}
	return out, rows.Err()
}

func (store *Store) ListDueSources(ctx context.Context, now int64) ([]Source, error) {
	rows, err := store.db.QueryContext(ctx, `
		SELECT id, account_id, remote_root_id, remote_root_path, local_alias,
			initial_mode, baseline_established, enabled, scan_interval_sec, min_file_size_bytes,
			last_scan_generation, last_scan_at, last_scan_status, last_scan_error,
			consecutive_failures, next_scan_at, created_at, updated_at
		FROM sources WHERE enabled = 1 AND next_scan_at <= ? ORDER BY next_scan_at ASC`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Source
	for rows.Next() {
		src, err := scanSourceRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, src)
	}
	return out, rows.Err()
}

func (store *Store) StartScanRun(ctx context.Context, sourceID string) (ScanRun, Source, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return ScanRun{}, Source{}, err
	}
	defer tx.Rollback()

	src, err := getSourceTx(ctx, tx, sourceID)
	if err != nil {
		return ScanRun{}, Source{}, err
	}
	var maxGen sql.NullInt64
	if err := tx.QueryRowContext(ctx, `SELECT MAX(generation) FROM scan_runs WHERE source_id = ?`, sourceID).Scan(&maxGen); err != nil {
		return ScanRun{}, Source{}, err
	}
	gen := maxGen.Int64 + 1
	if src.LastScanGeneration >= gen {
		gen = src.LastScanGeneration + 1
	}
	run := ScanRun{
		ID:         newID("scan"),
		SourceID:   sourceID,
		Generation: gen,
		Status:     "RUNNING",
		StartedAt:  time.Now().Unix(),
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO scan_runs(id, source_id, generation, status, error_message, entries_count, started_at, finished_at)
		VALUES(?,?,?,?,?,?,?,0)`,
		run.ID, run.SourceID, run.Generation, run.Status, "", 0, run.StartedAt,
	); err != nil {
		return ScanRun{}, Source{}, err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE sources SET last_scan_status = 'RUNNING', last_scan_error = '', updated_at = ? WHERE id = ?`,
		run.StartedAt, sourceID,
	); err != nil {
		return ScanRun{}, Source{}, err
	}
	src.LastScanStatus = "RUNNING"
	src.LastScanError = ""
	src.UpdatedAt = run.StartedAt
	if err := tx.Commit(); err != nil {
		return ScanRun{}, Source{}, err
	}
	return run, src, nil
}

func (store *Store) InsertScanEntries(ctx context.Context, entries []ScanEntry) error {
	if len(entries) == 0 {
		return nil
	}
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	stmt, err := tx.PrepareContext(ctx, `
		INSERT INTO scan_entries(
			scan_run_id, source_id, remote_id, parent_remote_id, relative_path,
			name, is_dir, size, gcid, version_key, strong_version, remote_modified_at
		) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	defer stmt.Close()

	for _, entry := range entries {
		if _, err := stmt.ExecContext(ctx,
			entry.ScanRunID, entry.SourceID, entry.RemoteID, entry.ParentRemoteID, entry.RelativePath,
			entry.Name, boolToInt(entry.IsDir), entry.Size, entry.GCID, entry.VersionKey, boolToInt(entry.StrongVersion), entry.RemoteModifiedAt,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (store *Store) FailScanRun(ctx context.Context, runID string, sourceID string, message string, nextRetryAt int64) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `DELETE FROM scan_entries WHERE scan_run_id = ?`, runID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE scan_runs SET status = 'FAILED', error_message = ?, finished_at = ? WHERE id = ?`, message, now, runID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE sources SET
			last_scan_at = ?, last_scan_status = 'FAILED', last_scan_error = ?,
			consecutive_failures = consecutive_failures + 1, next_scan_at = ?, updated_at = ?
		WHERE id = ?`,
		now, message, nextRetryAt, now, sourceID,
	); err != nil {
		return err
	}
	return tx.Commit()
}

type PublishResult struct {
	Source        Source
	EnqueuedTasks []DownloadTask
	EntriesCount  int
	MarkedMissing int
}

func (store *Store) PublishScanRun(ctx context.Context, runID string, maxAttempts int) (PublishResult, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return PublishResult{}, err
	}
	defer tx.Rollback()

	var status, sourceID string
	var generation int64
	if err := tx.QueryRowContext(ctx, `SELECT source_id, generation, status FROM scan_runs WHERE id = ?`, runID).Scan(&sourceID, &generation, &status); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return PublishResult{}, ErrNotFound
		}
		return PublishResult{}, err
	}
	if status != "RUNNING" {
		return PublishResult{}, ErrConflict
	}

	src, err := getSourceTx(ctx, tx, sourceID)
	if err != nil {
		return PublishResult{}, err
	}

	occupied := make(map[string]string)
	taskRows, err := tx.QueryContext(ctx, `SELECT remote_id, target_rel_path FROM download_tasks WHERE source_id = ?`, sourceID)
	if err != nil {
		return PublishResult{}, err
	}
	for taskRows.Next() {
		var remoteID, target string
		if err := taskRows.Scan(&remoteID, &target); err != nil {
			taskRows.Close()
			return PublishResult{}, err
		}
		occupied[localfs.NormalizedCollisionKey(target)] = remoteID
	}
	taskRows.Close()

	delivRows, err := tx.QueryContext(ctx, `SELECT remote_id, local_rel_path FROM deliveries`)
	if err != nil {
		return PublishResult{}, err
	}
	for delivRows.Next() {
		var remoteID, localRel string
		if err := delivRows.Scan(&remoteID, &localRel); err != nil {
			delivRows.Close()
			return PublishResult{}, err
		}
		key := localfs.NormalizedCollisionKey(localRel)
		if _, exists := occupied[key]; !exists {
			occupied[key] = remoteID
		}
	}
	delivRows.Close()

	entryRows, err := tx.QueryContext(ctx, `
		SELECT remote_id, parent_remote_id, relative_path, name, is_dir, size, gcid, version_key, strong_version, remote_modified_at
		FROM scan_entries WHERE scan_run_id = ? ORDER BY relative_path ASC`, runID)
	if err != nil {
		return PublishResult{}, err
	}
	var entries []ScanEntry
	for entryRows.Next() {
		var entry ScanEntry
		var isDir, strong int
		if err := entryRows.Scan(
			&entry.RemoteID, &entry.ParentRemoteID, &entry.RelativePath, &entry.Name,
			&isDir, &entry.Size, &entry.GCID, &entry.VersionKey, &strong, &entry.RemoteModifiedAt,
		); err != nil {
			entryRows.Close()
			return PublishResult{}, err
		}
		entry.ScanRunID = runID
		entry.SourceID = sourceID
		entry.IsDir = isDir == 1
		entry.StrongVersion = strong == 1
		entries = append(entries, entry)
	}
	entryRows.Close()
	if err := entryRows.Err(); err != nil {
		return PublishResult{}, err
	}

	now := time.Now().Unix()
	establishBaseline := src.InitialMode == InitialModeMonitorNewOnly && !src.BaselineEstablished
	var enqueued []DownloadTask

	for _, entry := range entries {
		var prevBaselineKey string
		_ = tx.QueryRowContext(ctx, `SELECT baseline_version_key FROM remote_objects WHERE source_id = ? AND remote_id = ?`, sourceID, entry.RemoteID).Scan(&prevBaselineKey)
		baselineKey := prevBaselineKey
		if establishBaseline && !entry.IsDir {
			baselineKey = entry.VersionKey
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO remote_objects(
				id, source_id, remote_id, parent_remote_id, relative_path, name,
				is_dir, size, gcid, version_key, baseline_version_key, strong_version, remote_modified_at,
				first_seen_at, last_seen_at, last_seen_generation, miss_count, remote_state, missing_since
			) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,'PRESENT',0)
			ON CONFLICT(source_id, remote_id) DO UPDATE SET
				parent_remote_id = excluded.parent_remote_id,
				relative_path = excluded.relative_path,
				name = excluded.name,
				is_dir = excluded.is_dir,
				size = excluded.size,
				gcid = excluded.gcid,
				version_key = excluded.version_key,
				baseline_version_key = excluded.baseline_version_key,
				strong_version = excluded.strong_version,
				remote_modified_at = excluded.remote_modified_at,
				last_seen_at = excluded.last_seen_at,
				last_seen_generation = excluded.last_seen_generation,
				miss_count = 0,
				remote_state = 'PRESENT',
				missing_since = 0`,
			newID("obj"), sourceID, entry.RemoteID, entry.ParentRemoteID, entry.RelativePath, entry.Name,
			boolToInt(entry.IsDir), entry.Size, entry.GCID, entry.VersionKey, baselineKey, boolToInt(entry.StrongVersion), entry.RemoteModifiedAt,
			now, now, generation,
		); err != nil {
			return PublishResult{}, err
		}

		if entry.IsDir || establishBaseline {
			continue
		}
		if baselineKey != "" && baselineKey == entry.VersionKey {
			continue
		}
		if src.MinFileSizeBytes > 0 && entry.Size < src.MinFileSizeBytes {
			continue
		}

		var existingTaskID string
		err := tx.QueryRowContext(ctx, `
			SELECT id FROM download_tasks WHERE source_id = ? AND remote_id = ? AND version_key = ?`,
			sourceID, entry.RemoteID, entry.VersionKey,
		).Scan(&existingTaskID)
		if err == nil {
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return PublishResult{}, err
		}

		targetRel, err := localfs.MapRelativePath(src.LocalAlias, entry.RelativePath, entry.RemoteID, occupied)
		if err != nil {
			return PublishResult{}, fmt.Errorf("map path for %s: %w", entry.RemoteID, err)
		}
		occupied[localfs.NormalizedCollisionKey(targetRel)] = entry.RemoteID

		taskID := newID("tsk")
		stagingRel, err := localfs.StagingRelPath(taskID)
		if err != nil {
			return PublishResult{}, err
		}
		task := DownloadTask{
			ID:             taskID,
			SourceID:       sourceID,
			RemoteID:       entry.RemoteID,
			VersionKey:     entry.VersionKey,
			GCID:           entry.GCID,
			StrongVersion:  entry.StrongVersion,
			RemotePath:     entry.RelativePath,
			TargetRelPath:  targetRel,
			State:          TaskQueued,
			MaxAttempts:    maxAttempts,
			BytesTotal:     entry.Size,
			StagingRelPath: stagingRel,
			CreatedAt:      now,
			UpdatedAt:      now,
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO download_tasks(
				id, source_id, remote_id, version_key, gcid, strong_version,
				remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
				priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
				error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
			) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,0)`,
			task.ID, task.SourceID, task.RemoteID, task.VersionKey, task.GCID, boolToInt(task.StrongVersion),
			task.RemotePath, task.TargetRelPath, task.State, DesiredNone, 0, task.MaxAttempts,
			0, task.BytesTotal, 0, task.StagingRelPath, "",
			"", "", 0, task.CreatedAt, task.UpdatedAt,
		); err != nil {
			return PublishResult{}, err
		}
		enqueued = append(enqueued, task)
	}

	if _, err := tx.ExecContext(ctx, `
		UPDATE remote_objects
		SET miss_count = miss_count + 1
		WHERE source_id = ? AND last_seen_generation < ? AND remote_state = 'PRESENT'`,
		sourceID, generation,
	); err != nil {
		return PublishResult{}, err
	}
	res, err := tx.ExecContext(ctx, `
		UPDATE remote_objects
		SET remote_state = 'OUT_OF_SCOPE', missing_since = ?
		WHERE source_id = ? AND last_seen_generation < ? AND miss_count >= 2 AND remote_state = 'PRESENT'`,
		now, sourceID, generation,
	)
	if err != nil {
		return PublishResult{}, err
	}
	markedMissing, _ := res.RowsAffected()

	if _, err := tx.ExecContext(ctx, `
		UPDATE scan_runs SET status = 'COMMITTED', entries_count = ?, finished_at = ? WHERE id = ?`,
		len(entries), now, runID,
	); err != nil {
		return PublishResult{}, err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM scan_entries WHERE scan_run_id = ?`, runID); err != nil {
		return PublishResult{}, err
	}
	nextScan := now + int64(src.ScanIntervalSec)
	if _, err := tx.ExecContext(ctx, `
		UPDATE sources SET
			baseline_established = 1, last_scan_generation = ?, last_scan_at = ?,
			last_scan_status = 'SUCCEEDED', last_scan_error = '',
			consecutive_failures = 0, next_scan_at = ?, updated_at = ?
		WHERE id = ?`,
		generation, now, nextScan, now, sourceID,
	); err != nil {
		return PublishResult{}, err
	}
	updatedSrc, err := getSourceTx(ctx, tx, sourceID)
	if err != nil {
		return PublishResult{}, err
	}
	if err := tx.Commit(); err != nil {
		return PublishResult{}, err
	}
	return PublishResult{
		Source:        updatedSrc,
		EnqueuedTasks: enqueued,
		EntriesCount:  len(entries),
		MarkedMissing: int(markedMissing),
	}, nil
}

func (store *Store) ClaimNextTask(ctx context.Context, now int64) (*DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var taskID string
	err = tx.QueryRowContext(ctx, `
		SELECT t.id
		FROM download_tasks t
		JOIN sources s ON s.id = t.source_id
		LEFT JOIN remote_objects r ON r.source_id = t.source_id AND r.remote_id = t.remote_id
		WHERE s.enabled = 1
			AND COALESCE(r.remote_state, 'PRESENT') = 'PRESENT'
			AND t.bytes_total >= s.min_file_size_bytes
			AND t.desired_action = ''
			AND (t.state = 'QUEUED' OR (t.state = 'RETRY_WAIT' AND t.next_retry_at <= ?))
		ORDER BY t.priority DESC, t.created_at ASC
		LIMIT 1`, now).Scan(&taskID)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}

	res, err := tx.ExecContext(ctx, `
		UPDATE download_tasks
		SET state = 'RUNNING',
			attempt = attempt + CASE WHEN resume_pending = 1 THEN 0 ELSE 1 END,
			resume_pending = 0,
			error_code = '', error_message = '',
			next_retry_at = 0, started_at = ?, updated_at = ?
		WHERE id = ? AND desired_action = '' AND (state = 'QUEUED' OR state = 'RETRY_WAIT')`,
		now, now, taskID,
	)
	if err != nil {
		return nil, err
	}
	if rows, _ := res.RowsAffected(); rows == 0 {
		return nil, nil
	}
	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return &task, nil
}

func (store *Store) GetTask(ctx context.Context, id string) (DownloadTask, error) {
	return getTaskQuery(ctx, store.db, id)
}

func (store *Store) ListTasks(ctx context.Context, stateFilter string, sourceID string, limit int, offset int) ([]DownloadTask, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	if offset < 0 {
		offset = 0
	}
	query := `SELECT id, source_id, remote_id, version_key, gcid, strong_version,
		remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
		priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
		local_existing, error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
		FROM download_tasks WHERE 1=1`
	var args []any
	if strings.TrimSpace(stateFilter) != "" {
		query += ` AND state = ?`
		args = append(args, strings.TrimSpace(stateFilter))
	}
	if strings.TrimSpace(sourceID) != "" {
		query += ` AND source_id = ?`
		args = append(args, strings.TrimSpace(sourceID))
	}
	query += ` ORDER BY updated_at DESC, created_at DESC LIMIT ? OFFSET ?`
	args = append(args, limit, offset)

	rows, err := store.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DownloadTask
	for rows.Next() {
		task, err := scanTaskRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, task)
	}
	return out, rows.Err()
}

func (store *Store) RequestPause(ctx context.Context, taskID string) (DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return DownloadTask{}, err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	now := time.Now().Unix()
	switch task.State {
	case TaskQueued, TaskRetryWait:
		if _, err := tx.ExecContext(ctx, `UPDATE download_tasks SET state = 'PAUSED', desired_action = '', updated_at = ? WHERE id = ?`, now, taskID); err != nil {
			return DownloadTask{}, err
		}
	case TaskRunning, TaskVerifying:
		if _, err := tx.ExecContext(ctx, `UPDATE download_tasks SET state = 'PAUSING', desired_action = 'PAUSE', updated_at = ? WHERE id = ?`, now, taskID); err != nil {
			return DownloadTask{}, err
		}
	case TaskPausing, TaskPaused:
	default:
		return DownloadTask{}, ErrConflict
	}
	updated, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if err := tx.Commit(); err != nil {
		return DownloadTask{}, err
	}
	return updated, nil
}

func (store *Store) RequestResume(ctx context.Context, taskID string) (DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return DownloadTask{}, err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if task.State != TaskPaused {
		return DownloadTask{}, ErrConflict
	}
	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks SET state = 'QUEUED', desired_action = '',
			resume_pending = CASE WHEN attempt > 0 THEN 1 ELSE 0 END,
			error_code = '', error_message = '', next_retry_at = 0, updated_at = ?
		WHERE id = ? AND state = 'PAUSED'`, now, taskID); err != nil {
		return DownloadTask{}, err
	}
	updated, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if err := tx.Commit(); err != nil {
		return DownloadTask{}, err
	}
	return updated, nil
}

func (store *Store) RequestCancel(ctx context.Context, taskID string) (DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return DownloadTask{}, err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	now := time.Now().Unix()
	switch task.State {
	case TaskQueued, TaskRetryWait, TaskPaused, TaskFailed, TaskConflict:
		if _, err := tx.ExecContext(ctx, `UPDATE download_tasks SET state = 'CANCELED', desired_action = 'CANCEL', finished_at = ?, updated_at = ? WHERE id = ?`, now, now, taskID); err != nil {
			return DownloadTask{}, err
		}
	case TaskRunning, TaskVerifying, TaskPausing:
		if _, err := tx.ExecContext(ctx, `UPDATE download_tasks SET state = 'CANCELING', desired_action = 'CANCEL', updated_at = ? WHERE id = ?`, now, taskID); err != nil {
			return DownloadTask{}, err
		}
	case TaskCanceling, TaskCanceled:
	default:
		return DownloadTask{}, ErrConflict
	}
	updated, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if err := tx.Commit(); err != nil {
		return DownloadTask{}, err
	}
	return updated, nil
}

func (store *Store) RequestRetry(ctx context.Context, taskID string) (DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return DownloadTask{}, err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	switch task.State {
	case TaskFailed, TaskCanceled, TaskConflict:
	default:
		return DownloadTask{}, ErrConflict
	}
	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks SET state = 'QUEUED', desired_action = '', attempt = 0, resume_pending = 0, next_retry_at = 0,
			error_code = '', error_message = '', finished_at = 0, updated_at = ?
		WHERE id = ?`, now, taskID); err != nil {
		return DownloadTask{}, err
	}
	updated, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if err := tx.Commit(); err != nil {
		return DownloadTask{}, err
	}
	return updated, nil
}

func (store *Store) UpdateAttemptProgress(ctx context.Context, taskID string, attempt int, bytesDone int64, bytesTotal int64) error {
	now := time.Now().Unix()
	_, err := store.db.ExecContext(ctx, `
		UPDATE download_tasks
		SET bytes_done = ?, bytes_total = CASE WHEN ? > 0 THEN ? ELSE bytes_total END, updated_at = ?
		WHERE id = ? AND attempt = ? AND state IN ('RUNNING', 'PAUSING', 'CANCELING', 'VERIFYING')`,
		bytesDone, bytesTotal, bytesTotal, now, taskID, attempt,
	)
	return err
}

func (store *Store) TransitionToVerifying(ctx context.Context, taskID string, attempt int, bytesDone int64) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return err
	}
	if task.Attempt != attempt {
		return ErrConflict
	}
	if task.DesiredAction != DesiredNone {
		return ErrStoppedByIntent
	}
	if task.State != TaskRunning {
		return ErrConflict
	}
	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks SET state = 'VERIFYING', bytes_done = ?, updated_at = ?
		WHERE id = ? AND attempt = ? AND state = 'RUNNING' AND desired_action = ''`,
		bytesDone, now, taskID, attempt,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func (store *Store) BeginCommit(ctx context.Context, taskID string, attempt int, sha256 string, bytesTotal int64) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return err
	}
	if task.Attempt != attempt {
		return ErrConflict
	}
	if task.DesiredAction != DesiredNone {
		return ErrStoppedByIntent
	}
	if task.State != TaskRunning && task.State != TaskVerifying {
		return ErrConflict
	}
	now := time.Now().Unix()
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks
		SET state = 'COMMITTING', commit_sha256 = ?, bytes_total = ?, bytes_done = ?, updated_at = ?
		WHERE id = ? AND attempt = ? AND desired_action = '' AND state IN ('RUNNING', 'VERIFYING')`,
		sha256, bytesTotal, bytesTotal, now, taskID, attempt,
	); err != nil {
		return err
	}
	return tx.Commit()
}

func (store *Store) CompleteCommit(ctx context.Context, taskID string, attempt int, delivery Delivery) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return err
	}
	if attempt > 0 && task.Attempt != attempt {
		return ErrConflict
	}
	if task.State != TaskCommitting {
		return ErrConflict
	}
	if delivery.ID == "" {
		delivery.ID = newID("dlv")
	}
	now := time.Now().Unix()
	if delivery.DeliveredAt == 0 {
		delivery.DeliveredAt = now
	}
	if err := upsertDeliveryTx(ctx, tx, taskID, delivery); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks SET state = 'COMPLETED', local_existing = 0, bytes_done = ?, bytes_total = ?, finished_at = ?, updated_at = ?
		WHERE id = ?`,
		delivery.Size, delivery.Size, now, now, taskID,
	); err != nil {
		return err
	}
	if err := deleteSegmentsTx(ctx, tx, taskID); err != nil {
		return err
	}
	return tx.Commit()
}

// CompleteExisting 记录「目标文件已存在，直接采用本地文件」：任务标记完成，
// 交付记录带 preexisting 标记，不下载也不覆盖。
func (store *Store) CompleteExisting(ctx context.Context, taskID string, attempt int, delivery Delivery) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return err
	}
	if attempt > 0 && task.Attempt != attempt {
		return ErrConflict
	}
	switch task.State {
	case TaskQueued, TaskRunning, TaskVerifying, TaskCommitting, TaskConflict, TaskRetryWait, TaskFailed:
	default:
		return ErrConflict
	}
	if delivery.ID == "" {
		delivery.ID = newID("dlv")
	}
	now := time.Now().Unix()
	if delivery.DeliveredAt == 0 {
		delivery.DeliveredAt = now
	}
	delivery.Preexisting = true
	if err := upsertDeliveryTx(ctx, tx, taskID, delivery); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks
		SET state = 'COMPLETED', local_existing = 1, desired_action = '',
			bytes_done = bytes_total, commit_sha256 = '',
			error_code = '', error_message = '', next_retry_at = 0,
			finished_at = ?, updated_at = ?
		WHERE id = ?`,
		now, now, taskID,
	); err != nil {
		return err
	}
	if err := deleteSegmentsTx(ctx, tx, taskID); err != nil {
		return err
	}
	return tx.Commit()
}

func upsertDeliveryTx(ctx context.Context, tx *sql.Tx, taskID string, delivery Delivery) error {
	_, err := tx.ExecContext(ctx, `
		INSERT INTO deliveries(
			id, source_id, remote_id, version_key, task_id, local_rel_path,
			size, local_sha256, remote_gcid, verified_remote_hash, preexisting, delivered_at
		) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(local_rel_path) DO UPDATE SET
			source_id = excluded.source_id,
			remote_id = excluded.remote_id,
			version_key = excluded.version_key,
			task_id = excluded.task_id,
			size = excluded.size,
			local_sha256 = excluded.local_sha256,
			remote_gcid = excluded.remote_gcid,
			verified_remote_hash = excluded.verified_remote_hash,
			preexisting = excluded.preexisting,
			delivered_at = excluded.delivered_at`,
		delivery.ID, delivery.SourceID, delivery.RemoteID, delivery.VersionKey, taskID, delivery.LocalRelPath,
		delivery.Size, delivery.LocalSHA256, delivery.RemoteGCID, boolToInt(delivery.VerifiedRemoteHash),
		boolToInt(delivery.Preexisting), delivery.DeliveredAt,
	)
	return err
}

func (store *Store) FinalizeAttemptStop(ctx context.Context, taskID string, attempt int, bytesDone int64, finalState string, errorCode string, errorMessage string, nextRetryAt int64) (DownloadTask, error) {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return DownloadTask{}, err
	}
	defer tx.Rollback()

	task, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if attempt > 0 && task.Attempt != attempt {
		return DownloadTask{}, ErrConflict
	}
	resolvedState := finalState
	switch task.DesiredAction {
	case DesiredCancel:
		resolvedState = TaskCanceled
	case DesiredPause:
		resolvedState = TaskPaused
	}
	now := time.Now().Unix()
	finishedAt := int64(0)
	if resolvedState == TaskCanceled || resolvedState == TaskFailed || resolvedState == TaskConflict {
		finishedAt = now
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE download_tasks
		SET state = ?, desired_action = CASE WHEN ? = 'CANCELED' THEN 'CANCEL' ELSE '' END,
			bytes_done = ?, error_code = ?, error_message = ?, next_retry_at = ?,
			finished_at = ?, updated_at = ?
		WHERE id = ?`,
		resolvedState, resolvedState, bytesDone, errorCode, errorMessage, nextRetryAt, finishedAt, now, taskID,
	); err != nil {
		return DownloadTask{}, err
	}
	if resolvedState == TaskCanceled {
		if err := deleteSegmentsTx(ctx, tx, taskID); err != nil {
			return DownloadTask{}, err
		}
	}
	updated, err := getTaskTx(ctx, tx, taskID)
	if err != nil {
		return DownloadTask{}, err
	}
	if err := tx.Commit(); err != nil {
		return DownloadTask{}, err
	}
	return updated, nil
}

func (store *Store) ListNonTerminalTasks(ctx context.Context) ([]DownloadTask, error) {
	rows, err := store.db.QueryContext(ctx, `
		SELECT id, source_id, remote_id, version_key, gcid, strong_version,
			remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
			priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
			local_existing, error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
		FROM download_tasks
		WHERE state NOT IN ('COMPLETED', 'CANCELED', 'FAILED', 'CONFLICT')
		ORDER BY created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DownloadTask
	for rows.Next() {
		task, err := scanTaskRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, task)
	}
	return out, rows.Err()
}

type TaskAggregate struct {
	Queued         int
	Active         int
	Paused         int
	RemainingTasks int
	RemainingBytes int64
}

func (store *Store) TaskAggregate(ctx context.Context) (TaskAggregate, error) {
	var aggregate TaskAggregate
	err := store.db.QueryRowContext(ctx, `
		SELECT
			COALESCE(SUM(CASE WHEN state IN ('QUEUED', 'RETRY_WAIT') THEN 1 ELSE 0 END), 0),
			COALESCE(SUM(CASE WHEN state IN ('RUNNING', 'VERIFYING', 'COMMITTING') THEN 1 ELSE 0 END), 0),
			COALESCE(SUM(CASE WHEN state IN ('PAUSED', 'PAUSING') THEN 1 ELSE 0 END), 0),
			COALESCE(SUM(CASE WHEN state IN ('QUEUED', 'RETRY_WAIT', 'RUNNING', 'PAUSED', 'PAUSING', 'VERIFYING', 'COMMITTING') THEN 1 ELSE 0 END), 0),
			COALESCE(SUM(CASE
				WHEN state IN ('QUEUED', 'RETRY_WAIT', 'RUNNING', 'PAUSED', 'PAUSING', 'VERIFYING', 'COMMITTING')
					AND bytes_total > bytes_done THEN bytes_total - bytes_done
				ELSE 0 END), 0)
		FROM download_tasks`,
	).Scan(&aggregate.Queued, &aggregate.Active, &aggregate.Paused, &aggregate.RemainingTasks, &aggregate.RemainingBytes)
	return aggregate, err
}

func (store *Store) RecoverTaskState(ctx context.Context, taskID string, newState string, desiredAction string, bytesDone int64, errorCode string, errorMessage string) error {
	now := time.Now().Unix()
	finishedAt := int64(0)
	if newState == TaskCanceled || newState == TaskFailed || newState == TaskConflict {
		finishedAt = now
	}
	_, err := store.db.ExecContext(ctx, `
		UPDATE download_tasks
		SET state = ?, desired_action = ?, bytes_done = ?, error_code = ?, error_message = ?, finished_at = ?, updated_at = ?
		WHERE id = ?`,
		newState, desiredAction, bytesDone, errorCode, errorMessage, finishedAt, now, taskID,
	)
	return err
}

func (store *Store) ListFileViews(ctx context.Context, sourceID string, remoteStateFilter string, limit int, offset int) ([]FileView, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}
	if offset < 0 {
		offset = 0
	}
	query := `
		SELECT id, source_id, remote_id, parent_remote_id, relative_path, name,
			is_dir, size, gcid, version_key, baseline_version_key, strong_version, remote_modified_at,
			first_seen_at, last_seen_at, last_seen_generation, miss_count, remote_state, missing_since
		FROM remote_objects WHERE is_dir = 0`
	var args []any
	if strings.TrimSpace(sourceID) != "" {
		query += ` AND source_id = ?`
		args = append(args, strings.TrimSpace(sourceID))
	}
	if strings.TrimSpace(remoteStateFilter) != "" {
		query += ` AND remote_state = ?`
		args = append(args, strings.TrimSpace(remoteStateFilter))
	}
	query += ` ORDER BY relative_path ASC LIMIT ? OFFSET ?`
	args = append(args, limit, offset)

	rows, err := store.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	var objects []RemoteObject
	for rows.Next() {
		obj, err := scanRemoteObjectRow(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		objects = append(objects, obj)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	views := make([]FileView, 0, len(objects))
	for _, obj := range objects {
		view := FileView{RemoteObject: obj}
		dlv, err := store.getLatestDelivery(ctx, obj.SourceID, obj.RemoteID)
		if err == nil {
			view.Delivery = dlv
			src, srcErr := store.GetSource(ctx, obj.SourceID)
			if srcErr == nil {
				expected, mapErr := localfs.MapRelativePath(src.LocalAlias, obj.RelativePath, obj.RemoteID, nil)
				if mapErr == nil && expected != dlv.LocalRelPath {
					view.PathMoved = true
				}
			}
		}
		tsk, err := store.getLatestTask(ctx, obj.SourceID, obj.RemoteID)
		if err == nil {
			view.LatestTask = tsk
		}
		views = append(views, view)
	}
	return views, nil
}

func (store *Store) getLatestDelivery(ctx context.Context, sourceID string, remoteID string) (*Delivery, error) {
	var dlv Delivery
	var verified, preexisting int
	err := store.db.QueryRowContext(ctx, `
		SELECT id, source_id, remote_id, version_key, task_id, local_rel_path, size, local_sha256, remote_gcid, verified_remote_hash, preexisting, delivered_at
		FROM deliveries WHERE source_id = ? AND remote_id = ?
		ORDER BY delivered_at DESC LIMIT 1`, sourceID, remoteID).Scan(
		&dlv.ID, &dlv.SourceID, &dlv.RemoteID, &dlv.VersionKey, &dlv.TaskID, &dlv.LocalRelPath,
		&dlv.Size, &dlv.LocalSHA256, &dlv.RemoteGCID, &verified, &preexisting, &dlv.DeliveredAt,
	)
	if err != nil {
		return nil, err
	}
	dlv.VerifiedRemoteHash = verified == 1
	dlv.Preexisting = preexisting == 1
	return &dlv, nil
}

func (store *Store) getLatestTask(ctx context.Context, sourceID string, remoteID string) (*DownloadTask, error) {
	row := store.db.QueryRowContext(ctx, `
		SELECT id, source_id, remote_id, version_key, gcid, strong_version,
			remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
			priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
			local_existing, error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
		FROM download_tasks WHERE source_id = ? AND remote_id = ?
		ORDER BY created_at DESC LIMIT 1`, sourceID, remoteID)
	task, err := scanTaskRow(row)
	if err != nil {
		return nil, err
	}
	return &task, nil
}

func (store *Store) CreateAdminSession(ctx context.Context, sess AdminSession) error {
	_, err := store.db.ExecContext(ctx, `
		INSERT INTO admin_sessions(token_hash, username, csrf_token, expires_at, created_at)
		VALUES(?,?,?,?,?)`,
		sess.TokenHash, sess.Username, sess.CSRFToken, sess.ExpiresAt, sess.CreatedAt,
	)
	return err
}

func (store *Store) GetAdminSession(ctx context.Context, tokenHash string) (AdminSession, error) {
	var sess AdminSession
	err := store.db.QueryRowContext(ctx, `
		SELECT token_hash, username, csrf_token, expires_at, created_at
		FROM admin_sessions WHERE token_hash = ?`, tokenHash).Scan(
		&sess.TokenHash, &sess.Username, &sess.CSRFToken, &sess.ExpiresAt, &sess.CreatedAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return AdminSession{}, ErrNotFound
	}
	return sess, err
}

func (store *Store) DeleteAdminSession(ctx context.Context, tokenHash string) error {
	_, err := store.db.ExecContext(ctx, `DELETE FROM admin_sessions WHERE token_hash = ?`, tokenHash)
	return err
}

func (store *Store) PruneExpiredAdminSessions(ctx context.Context, now int64) error {
	_, err := store.db.ExecContext(ctx, `DELETE FROM admin_sessions WHERE expires_at <= ?`, now)
	return err
}

func (store *Store) HasAdmin(ctx context.Context) (bool, error) {
	var count int
	if err := store.db.QueryRowContext(ctx, `SELECT COUNT(1) FROM admin_users`).Scan(&count); err != nil {
		return false, err
	}
	return count > 0, nil
}

func (store *Store) GetAdmin(ctx context.Context) (AdminUser, error) {
	var admin AdminUser
	err := store.db.QueryRowContext(ctx, `
		SELECT id, username, password_hash, created_at, updated_at FROM admin_users WHERE id = 1`,
	).Scan(&admin.ID, &admin.Username, &admin.PasswordHash, &admin.CreatedAt, &admin.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return AdminUser{}, ErrNotFound
	}
	return admin, err
}

func (store *Store) CreateAdmin(ctx context.Context, username string, passwordHash string) (AdminUser, error) {
	now := time.Now().Unix()
	admin := AdminUser{
		ID:           1,
		Username:     strings.TrimSpace(username),
		PasswordHash: passwordHash,
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if admin.Username == "" || admin.PasswordHash == "" {
		return AdminUser{}, errors.New("admin username and password hash are required")
	}
	if _, err := store.db.ExecContext(ctx, `
		INSERT INTO admin_users(id, username, password_hash, created_at, updated_at)
		VALUES(1, ?, ?, ?, ?)`,
		admin.Username, admin.PasswordHash, admin.CreatedAt, admin.UpdatedAt,
	); err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return AdminUser{}, ErrConflict
		}
		return AdminUser{}, err
	}
	return admin, nil
}

func (store *Store) UpdateAdminPassword(ctx context.Context, username string, passwordHash string) error {
	res, err := store.db.ExecContext(ctx, `
		UPDATE admin_users SET username = ?, password_hash = ?, updated_at = ? WHERE id = 1`,
		strings.TrimSpace(username), passwordHash, time.Now().Unix(),
	)
	if err != nil {
		return err
	}
	if rows, _ := res.RowsAffected(); rows == 0 {
		return ErrNotFound
	}
	return nil
}

func (store *Store) ListNotificationChannels(ctx context.Context) ([]NotificationChannel, error) {
	rows, err := store.db.QueryContext(ctx, `
		SELECT id, name, type, url_cipher, secret_cipher, events_json, enabled, created_at, updated_at
		FROM notification_channels ORDER BY created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []NotificationChannel
	for rows.Next() {
		channel, err := scanNotificationChannelRow(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, channel)
	}
	return out, rows.Err()
}

func (store *Store) GetNotificationChannel(ctx context.Context, id string) (NotificationChannel, error) {
	row := store.db.QueryRowContext(ctx, `
		SELECT id, name, type, url_cipher, secret_cipher, events_json, enabled, created_at, updated_at
		FROM notification_channels WHERE id = ?`, id)
	channel, err := scanNotificationChannelRow(row)
	if errors.Is(err, sql.ErrNoRows) {
		return NotificationChannel{}, ErrNotFound
	}
	return channel, err
}

func (store *Store) CreateNotificationChannel(ctx context.Context, channel NotificationChannel) (NotificationChannel, error) {
	if channel.ID == "" {
		channel.ID = newID("ntf")
	}
	now := time.Now().Unix()
	channel.CreatedAt = now
	channel.UpdatedAt = now
	_, err := store.db.ExecContext(ctx, `
		INSERT INTO notification_channels(
			id, name, type, url_cipher, secret_cipher, events_json, enabled, created_at, updated_at
		) VALUES(?,?,?,?,?,?,?,?,?)`,
		channel.ID, channel.Name, channel.Type, channel.URLCipher, channel.SecretCipher,
		channel.EventsJSON, boolToInt(channel.Enabled), channel.CreatedAt, channel.UpdatedAt,
	)
	return channel, err
}

func (store *Store) UpdateNotificationChannel(ctx context.Context, channel NotificationChannel) (NotificationChannel, error) {
	channel.UpdatedAt = time.Now().Unix()
	res, err := store.db.ExecContext(ctx, `
		UPDATE notification_channels SET name = ?, type = ?, url_cipher = ?, secret_cipher = ?,
			events_json = ?, enabled = ?, updated_at = ? WHERE id = ?`,
		channel.Name, channel.Type, channel.URLCipher, channel.SecretCipher,
		channel.EventsJSON, boolToInt(channel.Enabled), channel.UpdatedAt, channel.ID,
	)
	if err != nil {
		return NotificationChannel{}, err
	}
	if rows, _ := res.RowsAffected(); rows == 0 {
		return NotificationChannel{}, ErrNotFound
	}
	return channel, nil
}

func (store *Store) DeleteNotificationChannel(ctx context.Context, id string) error {
	res, err := store.db.ExecContext(ctx, `DELETE FROM notification_channels WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if rows, _ := res.RowsAffected(); rows == 0 {
		return ErrNotFound
	}
	return nil
}

func scanNotificationChannelRow(row rowScanner) (NotificationChannel, error) {
	var channel NotificationChannel
	var enabled int
	if err := row.Scan(
		&channel.ID, &channel.Name, &channel.Type, &channel.URLCipher, &channel.SecretCipher,
		&channel.EventsJSON, &enabled, &channel.CreatedAt, &channel.UpdatedAt,
	); err != nil {
		return NotificationChannel{}, err
	}
	channel.Enabled = enabled == 1
	return channel, nil
}

func (store *Store) GetSettings(ctx context.Context) (map[string]string, error) {
	rows, err := store.db.QueryContext(ctx, `SELECT key, value FROM app_settings`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]string)
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			return nil, err
		}
		out[key] = value
	}
	return out, rows.Err()
}

func (store *Store) SaveSettings(ctx context.Context, values map[string]string) error {
	if len(values) == 0 {
		return nil
	}
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	now := time.Now().Unix()
	for key, value := range values {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO app_settings(key, value, updated_at) VALUES(?,?,?)
			ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
			key, value, now,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (store *Store) HasActiveTasks(ctx context.Context) (bool, error) {
	var count int
	err := store.db.QueryRowContext(ctx, `
		SELECT COUNT(1) FROM download_tasks
		WHERE state IN ('RUNNING', 'PAUSING', 'CANCELING', 'VERIFYING', 'COMMITTING')`).Scan(&count)
	return count > 0, err
}

func (store *Store) DeleteSource(ctx context.Context, id string) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := getSourceTx(ctx, tx, id); err != nil {
		return err
	}
	var activeCount int
	if err := tx.QueryRowContext(ctx, `
		SELECT COUNT(1) FROM download_tasks
		WHERE source_id = ? AND state IN ('RUNNING', 'PAUSING', 'CANCELING', 'VERIFYING', 'COMMITTING')`,
		id,
	).Scan(&activeCount); err != nil {
		return err
	}
	if activeCount > 0 {
		return ErrConflict
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM sources WHERE id = ?`, id); err != nil {
		return err
	}
	return tx.Commit()
}

type rowScanner interface {
	Scan(dest ...any) error
}

type queryRunner interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

func getSourceTx(ctx context.Context, tx *sql.Tx, id string) (Source, error) {
	return getSourceQuery(ctx, tx, id)
}

func getSourceQuery(ctx context.Context, runner queryRunner, id string) (Source, error) {
	row := runner.QueryRowContext(ctx, `
		SELECT id, account_id, remote_root_id, remote_root_path, local_alias,
			initial_mode, baseline_established, enabled, scan_interval_sec, min_file_size_bytes,
			last_scan_generation, last_scan_at, last_scan_status, last_scan_error,
			consecutive_failures, next_scan_at, created_at, updated_at
		FROM sources WHERE id = ?`, id)
	src, err := scanSourceRow(row)
	if errors.Is(err, sql.ErrNoRows) {
		return Source{}, ErrNotFound
	}
	return src, err
}

func scanSourceRow(row rowScanner) (Source, error) {
	var src Source
	var baseline, enabled int
	err := row.Scan(
		&src.ID, &src.AccountID, &src.RemoteRootID, &src.RemoteRootPath, &src.LocalAlias,
		&src.InitialMode, &baseline, &enabled, &src.ScanIntervalSec, &src.MinFileSizeBytes,
		&src.LastScanGeneration, &src.LastScanAt, &src.LastScanStatus, &src.LastScanError,
		&src.ConsecutiveFailures, &src.NextScanAt, &src.CreatedAt, &src.UpdatedAt,
	)
	if err != nil {
		return Source{}, err
	}
	src.BaselineEstablished = baseline == 1
	src.Enabled = enabled == 1
	return src, nil
}

func getTaskTx(ctx context.Context, tx *sql.Tx, id string) (DownloadTask, error) {
	return getTaskQuery(ctx, tx, id)
}

func getTaskQuery(ctx context.Context, runner queryRunner, id string) (DownloadTask, error) {
	row := runner.QueryRowContext(ctx, `
		SELECT id, source_id, remote_id, version_key, gcid, strong_version,
			remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
			priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
			local_existing, error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
		FROM download_tasks WHERE id = ?`, id)
	task, err := scanTaskRow(row)
	if errors.Is(err, sql.ErrNoRows) {
		return DownloadTask{}, ErrNotFound
	}
	return task, err
}

func scanTaskRow(row rowScanner) (DownloadTask, error) {
	var task DownloadTask
	var strong, localExisting int
	err := row.Scan(
		&task.ID, &task.SourceID, &task.RemoteID, &task.VersionKey, &task.GCID, &strong,
		&task.RemotePath, &task.TargetRelPath, &task.State, &task.DesiredAction, &task.Attempt, &task.MaxAttempts,
		&task.Priority, &task.BytesTotal, &task.BytesDone, &task.StagingRelPath, &task.CommitSHA256,
		&localExisting, &task.ErrorCode, &task.ErrorMessage, &task.NextRetryAt, &task.CreatedAt, &task.UpdatedAt, &task.StartedAt, &task.FinishedAt,
	)
	if err != nil {
		return DownloadTask{}, err
	}
	task.StrongVersion = strong == 1
	task.LocalExisting = localExisting == 1
	return task, nil
}

func scanRemoteObjectRow(row rowScanner) (RemoteObject, error) {
	var obj RemoteObject
	var isDir, strong int
	err := row.Scan(
		&obj.ID, &obj.SourceID, &obj.RemoteID, &obj.ParentRemoteID, &obj.RelativePath, &obj.Name,
		&isDir, &obj.Size, &obj.GCID, &obj.VersionKey, &obj.BaselineVersionKey, &strong, &obj.RemoteModifiedAt,
		&obj.FirstSeenAt, &obj.LastSeenAt, &obj.LastSeenGeneration, &obj.MissCount, &obj.RemoteState, &obj.MissingSince,
	)
	if err != nil {
		return RemoteObject{}, err
	}
	obj.IsDir = isDir == 1
	obj.StrongVersion = strong == 1
	return obj, nil
}

func boolToInt(val bool) int {
	if val {
		return 1
	}
	return 0
}
