package store

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestScanPublishBaselineAndConsecutiveMisses(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("Open store failed: %v", err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID:       "acct_1",
		RemoteRootID:    "dir_1",
		RemoteRootPath:  "/Shows",
		LocalAlias:      "Shows",
		InitialMode:     InitialModeMonitorNewOnly,
		Enabled:         true,
		ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatalf("CreateSource failed: %v", err)
	}

	run1, _, err := st.StartScanRun(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	srcRunning, err := st.GetSource(ctx, src.ID)
	if err != nil || srcRunning.LastScanStatus != "RUNNING" {
		t.Fatalf("expected source status RUNNING after StartScanRun, got %+v err=%v", srcRunning, err)
	}
	if err := st.InsertScanEntries(ctx, []ScanEntry{
		{
			ScanRunID: run1.ID, SourceID: src.ID, RemoteID: "f1", ParentRemoteID: "dir_1",
			RelativePath: "ep1.mkv", Name: "ep1.mkv", Size: 100, GCID: "ABC", VersionKey: "gcid:ABC:100",
			StrongVersion: true, RemoteModifiedAt: time.Now().Unix(),
		},
	}); err != nil {
		t.Fatal(err)
	}
	pub1, err := st.PublishScanRun(ctx, run1.ID, 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(pub1.EnqueuedTasks) != 0 {
		t.Fatalf("expected baseline scan to enqueue 0 tasks, got %d", len(pub1.EnqueuedTasks))
	}

	run2, _, err := st.StartScanRun(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.InsertScanEntries(ctx, []ScanEntry{
		{
			ScanRunID: run2.ID, SourceID: src.ID, RemoteID: "f2", ParentRemoteID: "dir_1",
			RelativePath: "ep2.mkv", Name: "ep2.mkv", Size: 200, GCID: "DEF", VersionKey: "gcid:DEF:200",
			StrongVersion: true, RemoteModifiedAt: time.Now().Unix(),
		},
	}); err != nil {
		t.Fatal(err)
	}
	pub2, err := st.PublishScanRun(ctx, run2.ID, 5)
	if err != nil {
		t.Fatal(err)
	}
	if len(pub2.EnqueuedTasks) != 1 || pub2.MarkedMissing != 0 {
		t.Fatalf("expected 1 enqueued and 0 missing after 1st miss, got %d / %d", len(pub2.EnqueuedTasks), pub2.MarkedMissing)
	}

	run3, _, err := st.StartScanRun(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.InsertScanEntries(ctx, []ScanEntry{
		{
			ScanRunID: run3.ID, SourceID: src.ID, RemoteID: "f2", ParentRemoteID: "dir_1",
			RelativePath: "ep2.mkv", Name: "ep2.mkv", Size: 200, GCID: "DEF", VersionKey: "gcid:DEF:200",
			StrongVersion: true, RemoteModifiedAt: time.Now().Unix(),
		},
	}); err != nil {
		t.Fatal(err)
	}
	pub3, err := st.PublishScanRun(ctx, run3.ID, 5)
	if err != nil {
		t.Fatal(err)
	}
	if pub3.MarkedMissing != 1 {
		t.Fatalf("expected 1 marked missing after 2 consecutive misses, got %d", pub3.MarkedMissing)
	}
}

func TestCompleteExistingMarksTaskAndUpsertsDelivery(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("Open store failed: %v", err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID:       "acct_1",
		RemoteRootID:    "dir_1",
		RemoteRootPath:  "/Movies",
		LocalAlias:      "Movies",
		InitialMode:     InitialModeDownloadExisting,
		Enabled:         true,
		ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatal(err)
	}

	enqueue := func(versionKey string, size int64) DownloadTask {
		run, _, err := st.StartScanRun(ctx, src.ID)
		if err != nil {
			t.Fatal(err)
		}
		if err := st.InsertScanEntries(ctx, []ScanEntry{{
			ScanRunID: run.ID, SourceID: src.ID, RemoteID: "f1", ParentRemoteID: "dir_1",
			RelativePath: "movie.mkv", Name: "movie.mkv", Size: size, GCID: "G1",
			VersionKey: versionKey, StrongVersion: true, RemoteModifiedAt: time.Now().Unix(),
		}}); err != nil {
			t.Fatal(err)
		}
		pub, err := st.PublishScanRun(ctx, run.ID, 5)
		if err != nil {
			t.Fatal(err)
		}
		if len(pub.EnqueuedTasks) != 1 {
			t.Fatalf("expected 1 enqueued task, got %d", len(pub.EnqueuedTasks))
		}
		return pub.EnqueuedTasks[0]
	}
	adopt := func(task DownloadTask, size int64) {
		claimed, err := st.ClaimNextTask(ctx, time.Now().Unix())
		if err != nil || claimed == nil {
			t.Fatalf("ClaimNextTask failed: %v", err)
		}
		if err := st.CompleteExisting(ctx, claimed.ID, claimed.Attempt, Delivery{
			SourceID:     claimed.SourceID,
			RemoteID:     claimed.RemoteID,
			VersionKey:   claimed.VersionKey,
			LocalRelPath: claimed.TargetRelPath,
			Size:         size,
			RemoteGCID:   claimed.GCID,
		}); err != nil {
			t.Fatalf("CompleteExisting failed: %v", err)
		}
	}

	task1 := enqueue("gcid:G1:10", 10)
	adopt(task1, 10)
	got1, err := st.GetTask(ctx, task1.ID)
	if err != nil || got1.State != TaskCompleted || !got1.LocalExisting {
		t.Fatalf("expected COMPLETED local_existing task, got %+v err=%v", got1, err)
	}

	task2 := enqueue("gcid:G1:20", 20)
	adopt(task2, 20)
	views, err := st.ListFileViews(ctx, "", "", 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(views) != 1 || views[0].Delivery == nil {
		t.Fatalf("expected one file view with delivery, got %+v", views)
	}
	dlv := views[0].Delivery
	if !dlv.Preexisting || dlv.VersionKey != "gcid:G1:20" || dlv.Size != 20 {
		t.Fatalf("delivery was not upserted for the new version: %+v", dlv)
	}
}

func TestTaskCommitBoundaryAndControlIntents(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID: "acct_1", RemoteRootID: "dir_1", RemoteRootPath: "/Movies", LocalAlias: "Movies",
		InitialMode: InitialModeDownloadExisting, Enabled: true, ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatal(err)
	}
	run, _, err := st.StartScanRun(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	_ = st.InsertScanEntries(ctx, []ScanEntry{{
		ScanRunID: run.ID, SourceID: src.ID, RemoteID: "f1", ParentRemoteID: "dir_1",
		RelativePath: "m.mkv", Name: "m.mkv", Size: 50, GCID: "AAA", VersionKey: "gcid:AAA:50", StrongVersion: true,
	}})
	pub, err := st.PublishScanRun(ctx, run.ID, 5)
	if err != nil {
		t.Fatal(err)
	}
	task := pub.EnqueuedTasks[0]
	claimed, err := st.ClaimNextTask(ctx, time.Now().Unix())
	if err != nil || claimed == nil {
		t.Fatalf("ClaimNextTask failed: %v", err)
	}
	if err := st.BeginCommit(ctx, claimed.ID, claimed.Attempt, "deadbeef", 50); err != nil {
		t.Fatalf("BeginCommit failed: %v", err)
	}
	if _, err := st.RequestPause(ctx, task.ID); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict pausing committing task, got %v", err)
	}
	if _, err := st.RequestCancel(ctx, task.ID); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict canceling committing task, got %v", err)
	}
	if err := st.CompleteCommit(ctx, claimed.ID, claimed.Attempt, Delivery{
		SourceID: src.ID, RemoteID: "f1", VersionKey: task.VersionKey, LocalRelPath: task.TargetRelPath,
		Size: 50, LocalSHA256: "deadbeef", RemoteGCID: "AAA", VerifiedRemoteHash: false,
	}); err != nil {
		t.Fatalf("CompleteCommit failed: %v", err)
	}
}

func TestResumeDoesNotConsumeAttemptBudget(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID: "acct_1", RemoteRootID: "dir_1", RemoteRootPath: "/Movies", LocalAlias: "Movies",
		InitialMode: InitialModeDownloadExisting, Enabled: true, ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatal(err)
	}
	run, _, err := st.StartScanRun(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.InsertScanEntries(ctx, []ScanEntry{{
		ScanRunID: run.ID, SourceID: src.ID, RemoteID: "f1", ParentRemoteID: "dir_1",
		RelativePath: "m.mkv", Name: "m.mkv", Size: 50, GCID: "AAA", VersionKey: "gcid:AAA:50", StrongVersion: true,
	}}); err != nil {
		t.Fatal(err)
	}
	pub, err := st.PublishScanRun(ctx, run.ID, 5)
	if err != nil || len(pub.EnqueuedTasks) != 1 {
		t.Fatalf("publish failed: %+v err=%v", pub, err)
	}
	taskID := pub.EnqueuedTasks[0].ID

	claimed, err := st.ClaimNextTask(ctx, time.Now().Unix())
	if err != nil || claimed == nil || claimed.Attempt != 1 {
		t.Fatalf("first claim failed: %+v err=%v", claimed, err)
	}
	if _, err := st.RequestPause(ctx, taskID); err != nil {
		t.Fatal(err)
	}
	if _, err := st.FinalizeAttemptStop(ctx, taskID, claimed.Attempt, 0, TaskPaused, "", "", 0); err != nil {
		t.Fatal(err)
	}
	if _, err := st.RequestResume(ctx, taskID); err != nil {
		t.Fatal(err)
	}
	resumed, err := st.ClaimNextTask(ctx, time.Now().Unix())
	if err != nil || resumed == nil {
		t.Fatalf("resume claim failed: %+v err=%v", resumed, err)
	}
	if resumed.Attempt != 1 {
		t.Fatalf("expected resume to keep attempt at 1, got %d", resumed.Attempt)
	}
}

func TestListTasksOrdersByCreationInsteadOfLastUpdate(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID: "acct_1", RemoteRootID: "dir_1", RemoteRootPath: "/Movies", LocalAlias: "Movies",
		InitialMode: InitialModeDownloadExisting, Enabled: true, ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatal(err)
	}
	for index, taskID := range []string{"tsk_1", "tsk_2", "tsk_3"} {
		if _, err := st.db.ExecContext(ctx, `
			INSERT INTO download_tasks(
				id, source_id, remote_id, version_key, gcid, strong_version,
				remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
				priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
				error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
			) VALUES(?,?,?,?,?,1,?,?,?,?,0,5,0,100,0,?,?,?,?,0,1000,?,0,0)`,
			taskID, src.ID, taskID, "gcid:"+taskID+":100", taskID,
			taskID+".mkv", "Movies/"+taskID+".mkv", TaskQueued, DesiredNone,
			"staging/"+taskID, "", "", "", int64(1000+index),
		); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.db.ExecContext(ctx, `UPDATE download_tasks SET updated_at = 5000 WHERE id = 'tsk_1'`); err != nil {
		t.Fatal(err)
	}

	tasks, err := st.ListTasks(ctx, "", "", 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(tasks) != 3 {
		t.Fatalf("expected 3 tasks, got %d", len(tasks))
	}
	got := []string{tasks[0].ID, tasks[1].ID, tasks[2].ID}
	want := []string{"tsk_3", "tsk_2", "tsk_1"}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("task order changed after progress update: got %v want %v", got, want)
		}
	}

	page, total, err := st.ListTasksPage(ctx, "", "", "", 2, 0)
	if err != nil {
		t.Fatal(err)
	}
	if total != 3 || len(page) != 2 || page[0].ID != "tsk_3" || page[1].ID != "tsk_2" {
		t.Fatalf("unexpected first page: total=%d tasks=%+v", total, page)
	}
	page, total, err = st.ListTasksPage(ctx, "", "", "tsk_1", 10, 0)
	if err != nil {
		t.Fatal(err)
	}
	if total != 1 || len(page) != 1 || page[0].ID != "tsk_1" {
		t.Fatalf("unexpected search page: total=%d tasks=%+v", total, page)
	}
	stateTotals, err := st.TaskStateTotals(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if stateTotals.Queued != 3 {
		t.Fatalf("expected 3 queued tasks in totals, got %+v", stateTotals)
	}
}

func TestQueueOrderUsesCreationTimeAndInsertionSequence(t *testing.T) {
	ctx := context.Background()
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	src, err := st.CreateSource(ctx, Source{
		AccountID: "acct_1", RemoteRootID: "dir_1", RemoteRootPath: "/Movies", LocalAlias: "Movies",
		InitialMode: InitialModeDownloadExisting, Enabled: true, ScanIntervalSec: 600,
	})
	if err != nil {
		t.Fatal(err)
	}
	insertTask := func(taskID string, createdAt int64) {
		if _, err := st.db.ExecContext(ctx, `
			INSERT INTO download_tasks(
				id, source_id, remote_id, version_key, gcid, strong_version,
				remote_path, target_rel_path, state, desired_action, attempt, max_attempts,
				priority, bytes_total, bytes_done, staging_rel_path, commit_sha256,
				error_code, error_message, next_retry_at, created_at, updated_at, started_at, finished_at
			) VALUES(?,?,?,?,?,1,?,?,?,?,0,5,0,100,0,?,?,?,?,0,?,?,0,0)`,
			taskID, src.ID, taskID, "gcid:"+taskID+":100", taskID,
			taskID+".mkv", "Movies/"+taskID+".mkv", TaskQueued, DesiredNone,
			"staging/"+taskID, "", "", "", createdAt, createdAt,
		); err != nil {
			t.Fatal(err)
		}
	}

	// created_at 是秒级时间戳；同一批入队的任务必须再由 rowid 保持稳定的入队顺序。
	insertTask("tsk_late", 3000)
	insertTask("tsk_early_first", 1000)
	insertTask("tsk_early_second", 1000)
	want := []string{"tsk_early_first", "tsk_early_second", "tsk_late"}

	ids, err := st.ListTaskIDsByStates(ctx, "", []string{TaskQueued})
	if err != nil {
		t.Fatal(err)
	}
	if len(ids) != len(want) {
		t.Fatalf("unexpected batch order: got %v want %v", ids, want)
	}
	for index := range want {
		if ids[index] != want[index] {
			t.Fatalf("unexpected batch order: got %v want %v", ids, want)
		}
	}

	for index, wantID := range want {
		claimed, err := st.ClaimNextTask(ctx, time.Now().Unix())
		if err != nil || claimed == nil {
			t.Fatalf("claim %d failed: task=%+v err=%v", index, claimed, err)
		}
		if claimed.ID != wantID {
			t.Fatalf("claim %d picked %s, want %s", index, claimed.ID, wantID)
		}
	}
}
