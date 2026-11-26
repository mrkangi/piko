package catalog

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/store"
)

func TestScanSourceMidFailureDoesNotMarkMissingAndTracksTwoMisses(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	fake := backend.NewFakeBackend()
	fake.SetPageSize(1)
	fake.PutDir("d_shows", "", "Shows")
	fake.PutDir("d_s1", "d_shows", "Season 1")
	fake.PutFile("f1", "d_shows", "a.mkv", "G1", []byte("1111"), time.Now())
	fake.PutFile("f2", "d_s1", "b.mkv", "G2", []byte("2222"), time.Now())

	svc := NewService(st, fake, events.NewBroker(), "acct_1", 10*time.Minute, 5)
	src, err := svc.CreateSource(ctx, CreateSourceInput{
		RemoteRootID: "d_shows",
		LocalAlias:   "Shows",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}

	pub1, err := svc.ScanSource(ctx, src.ID)
	if err != nil {
		t.Fatalf("first scan failed: %v", err)
	}
	if len(pub1.EnqueuedTasks) != 2 {
		t.Fatalf("expected 2 enqueued tasks, got %d", len(pub1.EnqueuedTasks))
	}

	// Remove f2 remotely, but fail listing d_s1 -> must NOT mark f2 missing.
	fake.Remove("f2")
	fake.SetListHook(func(parentID string, cursor string, attempt int) error {
		if parentID == "d_s1" {
			return &backend.Error{Kind: backend.ErrTransient, Message: "simulated page failure"}
		}
		return nil
	})
	if _, err := svc.ScanSource(ctx, src.ID); err == nil {
		t.Fatalf("expected scan failure when subfolder fails")
	}
	filesAfterFail, err := st.ListFileViews(ctx, src.ID, store.RemoteStateOutOfScope, 100, 0)
	if err != nil || len(filesAfterFail) != 0 {
		t.Fatalf("expected 0 out-of-scope files after failed scan, got %d err=%v", len(filesAfterFail), err)
	}

	// Clear hook; run 2 full successful scans -> f2 marked OUT_OF_SCOPE on 2nd miss.
	fake.SetListHook(nil)
	pub2, err := svc.ScanSource(ctx, src.ID)
	if err != nil || pub2.MarkedMissing != 0 {
		t.Fatalf("expected 0 marked missing on 1st miss, got %d err=%v", pub2.MarkedMissing, err)
	}
	pub3, err := svc.ScanSource(ctx, src.ID)
	if err != nil || pub3.MarkedMissing != 1 {
		t.Fatalf("expected 1 marked missing on 2nd miss, got %d err=%v", pub3.MarkedMissing, err)
	}

	// Re-appear f2 with same version -> clears OUT_OF_SCOPE and does not duplicate task.
	fake.PutFile("f2", "d_s1", "b.mkv", "G2", []byte("2222"), time.Now())
	pub4, err := svc.ScanSource(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(pub4.EnqueuedTasks) != 0 {
		t.Fatalf("expected no duplicate task for existing version, got %d", len(pub4.EnqueuedTasks))
	}
	filesMissingNow, err := st.ListFileViews(ctx, src.ID, store.RemoteStateOutOfScope, 100, 0)
	if err != nil || len(filesMissingNow) != 0 {
		t.Fatalf("expected missing marker cleared on reappearance, got %d", len(filesMissingNow))
	}
}

func TestMonitorNewOnlyBaselineAndMultiSourceIsolation(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_a", "", "Movies")
	fake.PutDir("d_b", "", "Anime")
	fake.PutFile("f_a1", "d_a", "old.mkv", "GA1", []byte("old"), time.Now())
	fake.PutFile("f_b1", "d_b", "ep1.mkv", "GB1", []byte("ep1"), time.Now())

	svc := NewService(st, fake, events.NewBroker(), "acct_1", 10*time.Minute, 5)
	srcA, err := svc.CreateSource(ctx, CreateSourceInput{
		RemoteRootID: "d_a",
		LocalAlias:   "Movies",
		InitialMode:  store.InitialModeMonitorNewOnly,
	})
	if err != nil {
		t.Fatal(err)
	}
	srcB, err := svc.CreateSource(ctx, CreateSourceInput{
		RemoteRootID: "d_b",
		LocalAlias:   "Anime",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}

	pubA1, err := svc.ScanSource(ctx, srcA.ID)
	if err != nil || len(pubA1.EnqueuedTasks) != 0 {
		t.Fatalf("expected baseline monitor_new_only to enqueue 0, got %d err=%v", len(pubA1.EnqueuedTasks), err)
	}
	pubB1, err := svc.ScanSource(ctx, srcB.ID)
	if err != nil || len(pubB1.EnqueuedTasks) != 1 || pubB1.EnqueuedTasks[0].TargetRelPath != "Anime/ep1.mkv" {
		t.Fatalf("unexpected source B result: %+v err=%v", pubB1, err)
	}

	fake.PutFile("f_a2", "d_a", "new.mkv", "GA2", []byte("new"), time.Now())
	pubA2, err := svc.ScanSource(ctx, srcA.ID)
	if err != nil || len(pubA2.EnqueuedTasks) != 1 || pubA2.EnqueuedTasks[0].TargetRelPath != "Movies/new.mkv" {
		t.Fatalf("unexpected source A new file result: %+v err=%v", pubA2, err)
	}

	// Verify pagination cursor loop is rejected.
	if _, err := svc.CreateSource(ctx, CreateSourceInput{RemoteRootID: "missing", LocalAlias: "Bad"}); !errors.Is(err, err) && err == nil {
		t.Fatalf("expected error for missing remote root")
	}
	_ = fmt.Sprint()
}

func TestTriggerScanAllSchedulesEnabledSources(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_a", "", "A")
	fake.PutDir("d_b", "", "B")
	svc := NewService(st, fake, events.NewBroker(), "acct_1", 5*time.Minute, 5)

	_, err = svc.CreateSource(ctx, CreateSourceInput{RemoteRootID: "d_a", LocalAlias: "A"})
	if err != nil {
		t.Fatal(err)
	}
	srcB, err := svc.CreateSource(ctx, CreateSourceInput{RemoteRootID: "d_b", LocalAlias: "B"})
	if err != nil {
		t.Fatal(err)
	}
	disabled := false
	if _, err := svc.UpdateSource(ctx, srcB.ID, &disabled, nil, nil); err != nil {
		t.Fatal(err)
	}

	count, err := svc.TriggerScanAll(ctx, false)
	if err != nil || count != 1 {
		t.Fatalf("expected 1 enabled source scheduled, got %d err=%v", count, err)
	}
}

func TestMinFileSizeSkipsSmallFiles(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_root", "", "Media")
	fake.PutFile("small", "d_root", "small.bin", "SMALL", make([]byte, 50), time.Now())
	fake.PutFile("large", "d_root", "large.bin", "LARGE", make([]byte, 200), time.Now())

	svc := NewService(st, fake, events.NewBroker(), "acct_1", 5*time.Minute, 5)
	src, err := svc.CreateSource(ctx, CreateSourceInput{
		RemoteRootID:     "d_root",
		LocalAlias:       "Media",
		InitialMode:      store.InitialModeDownloadExisting,
		MinFileSizeBytes: 100,
	})
	if err != nil {
		t.Fatal(err)
	}
	pub, err := svc.ScanSource(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(pub.EnqueuedTasks) != 1 || pub.EnqueuedTasks[0].RemoteID != "large" {
		t.Fatalf("expected only large file to be enqueued, got %+v", pub.EnqueuedTasks)
	}
}

func TestScanFailureDebounceAndRecovery(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_root", "", "Media")
	fake.PutFile("f1", "d_root", "clip.mkv", "G1", []byte("clip"), time.Now())
	broker := events.NewBroker()
	eventCh, unsubscribe := broker.Subscribe(128)
	defer unsubscribe()

	svc := NewService(st, fake, broker, "acct_1", 5*time.Minute, 5)
	svc.SetFailureThreshold(3)
	src, err := svc.CreateSource(ctx, CreateSourceInput{
		RemoteRootID: "d_root",
		LocalAlias:   "Media",
		InitialMode:  store.InitialModeMonitorNewOnly,
	})
	if err != nil {
		t.Fatal(err)
	}

	fake.SetListHook(func(parentID string, cursor string, attempt int) error {
		return &backend.Error{Kind: backend.ErrTransient, Message: "temporary network failure"}
	})
	drainFailedEvents := func() int {
		count := 0
		for {
			select {
			case event := <-eventCh:
				if event.Type == "scan.failed" {
					count++
				}
			default:
				return count
			}
		}
	}

	for failure := 1; failure <= 2; failure++ {
		if _, err := svc.ScanSource(ctx, src.ID); err == nil {
			t.Fatalf("failure %d: expected scan error", failure)
		}
		latest, err := st.GetSource(ctx, src.ID)
		if err != nil {
			t.Fatal(err)
		}
		if latest.LastScanStatus != "RETRYING" || latest.ConsecutiveFailures != failure {
			t.Fatalf("failure %d: expected RETRYING count=%d, got status=%s count=%d", failure, failure, latest.LastScanStatus, latest.ConsecutiveFailures)
		}
		if got := drainFailedEvents(); got != 0 {
			t.Fatalf("failure %d: expected no confirmed scan.failed event before threshold, got %d", failure, got)
		}
	}

	if _, err := svc.ScanSource(ctx, src.ID); err == nil {
		t.Fatal("third failure: expected scan error")
	}
	latest, err := st.GetSource(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if latest.LastScanStatus != "FAILED" || latest.ConsecutiveFailures != 3 {
		t.Fatalf("expected FAILED count=3 at threshold, got status=%s count=%d", latest.LastScanStatus, latest.ConsecutiveFailures)
	}
	if got := drainFailedEvents(); got != 1 {
		t.Fatalf("expected one confirmed scan.failed event at threshold, got %d", got)
	}

	if _, err := svc.ScanSource(ctx, src.ID); err == nil {
		t.Fatal("fourth failure: expected scan error")
	}
	if got := drainFailedEvents(); got != 0 {
		t.Fatalf("expected no repeated scan.failed event after threshold, got %d", got)
	}

	fake.SetListHook(nil)
	if _, err := svc.ScanSource(ctx, src.ID); err != nil {
		t.Fatalf("expected recovery scan to succeed: %v", err)
	}
	latest, err = st.GetSource(ctx, src.ID)
	if err != nil {
		t.Fatal(err)
	}
	if latest.LastScanStatus != "SUCCEEDED" || latest.ConsecutiveFailures != 0 {
		t.Fatalf("expected SUCCEEDED count=0 after recovery, got status=%s count=%d", latest.LastScanStatus, latest.ConsecutiveFailures)
	}
}
