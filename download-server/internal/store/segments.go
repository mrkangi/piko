package store

import (
	"context"
	"database/sql"
	"fmt"
)

func (store *Store) ListSegments(ctx context.Context, taskID string) ([]DownloadSegment, error) {
	rows, err := store.db.QueryContext(ctx, `
		SELECT task_id, segment_index, start_offset, end_offset, done_offset
		FROM download_task_segments WHERE task_id = ?
		ORDER BY segment_index ASC`, taskID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var segments []DownloadSegment
	for rows.Next() {
		var segment DownloadSegment
		if err := rows.Scan(&segment.TaskID, &segment.Index, &segment.StartOffset, &segment.EndOffset, &segment.DoneOffset); err != nil {
			return nil, err
		}
		segments = append(segments, segment)
	}
	return segments, rows.Err()
}

func (store *Store) ReplaceSegments(ctx context.Context, taskID string, segments []DownloadSegment) error {
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if err := deleteSegmentsTx(ctx, tx, taskID); err != nil {
		return err
	}
	for _, segment := range segments {
		if segment.Index < 0 || segment.StartOffset < 0 || segment.EndOffset <= segment.StartOffset {
			return fmt.Errorf("invalid segment %d: [%d,%d)", segment.Index, segment.StartOffset, segment.EndOffset)
		}
		done := segment.DoneOffset
		if done < segment.StartOffset {
			done = segment.StartOffset
		}
		if done > segment.EndOffset {
			done = segment.EndOffset
		}
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO download_task_segments(task_id, segment_index, start_offset, end_offset, done_offset)
			VALUES(?,?,?,?,?)`,
			taskID, segment.Index, segment.StartOffset, segment.EndOffset, done,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (store *Store) UpdateSegments(ctx context.Context, taskID string, doneOffsets map[int]int64) error {
	if len(doneOffsets) == 0 {
		return nil
	}
	tx, err := store.beginImmediate(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for index, done := range doneOffsets {
		if _, err := tx.ExecContext(ctx, `
			UPDATE download_task_segments
			SET done_offset = MIN(end_offset, MAX(start_offset, ?))
			WHERE task_id = ? AND segment_index = ?`,
			done, taskID, index,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (store *Store) DeleteSegments(ctx context.Context, taskID string) error {
	_, err := store.db.ExecContext(ctx, `DELETE FROM download_task_segments WHERE task_id = ?`, taskID)
	return err
}

func deleteSegmentsTx(ctx context.Context, tx *sql.Tx, taskID string) error {
	_, err := tx.ExecContext(ctx, `DELETE FROM download_task_segments WHERE task_id = ?`, taskID)
	return err
}
