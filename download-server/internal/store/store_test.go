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
