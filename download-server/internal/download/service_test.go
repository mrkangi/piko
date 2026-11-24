package download

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/store"
)

type gatedReader struct {
	data       []byte
	pos        int
	gateOffset int
	reached    chan struct{}
	release    chan struct{}
	once       bool
}

func (reader *gatedReader) Read(p []byte) (int, error) {
	if !reader.once && reader.pos >= reader.gateOffset {
		reader.once = true
		close(reader.reached)
		<-reader.release
		return 0, context.Canceled
	}
	if reader.pos >= len(reader.data) {
		return 0, io.EOF
	}
	end := reader.pos + min(len(p), 1024)
	if !reader.once && end > reader.gateOffset {
		end = reader.gateOffset
	}
	if end > len(reader.data) {
		end = len(reader.data)
	}
	n := copy(p, reader.data[reader.pos:end])
	reader.pos += n
	return n, nil
}

func (reader *gatedReader) Close() error {
	return nil
}

func TestPauseRestartResumeAndDeliverExactBytes(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	downloadDir := t.TempDir()

	payload := bytes.Repeat([]byte("PIKO-DATA-BLOCK-"), 2048) // 32 KiB
	fake := backend.NewFakeBackend()
	fake.PutDir("d_movies", "", "Movies")
	fake.PutFile("f_big", "d_movies", "demo.mkv", "GCIDBIG", payload, time.Now())

	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	fsStore, err := localfs.Open(downloadDir)
	if err != nil {
		t.Fatal(err)
	}

	broker := events.NewBroker()
	cat := catalog.NewService(st, fake, broker, "acct_1", 10*time.Minute, 5)
	src, err := cat.CreateSource(ctx, catalog.CreateSourceInput{
		RemoteRootID: "d_movies",
		LocalAlias:   "Movies",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}
	pub, err := cat.ScanSource(ctx, src.ID)
	if err != nil || len(pub.EnqueuedTasks) != 1 {
		t.Fatalf("ScanSource failed: %v", err)
	}
	taskID := pub.EnqueuedTasks[0].ID

	reached := make(chan struct{})
	release := make(chan struct{})
	fake.SetOpenHook(func(exp backend.OpenExpectation, attempt int) (*backend.OpenedStream, error) {
		if attempt == 1 {
			return &backend.OpenedStream{
				Reader:          &gatedReader{data: payload, gateOffset: 8192, reached: reached, release: release},
				Offset:          0,
				TotalSize:       int64(len(payload)),
				VerifiedVersion: "gcid:GCIDBIG:32768",
				Verifiable:      true,
			}, nil
		}
		return nil, nil
	})

	dl1 := NewService(st, fsStore, fake, catRemote(fake), broker, Options{MaxConcurrent: 1, NoProgressTimeout: 10 * time.Second, MaxRetries: 3})
	runCtx1, cancel1 := context.WithCancel(ctx)
	go dl1.RunLoop(runCtx1)

	<-reached
	if _, err := dl1.PauseTask(ctx, taskID); err != nil {
		t.Fatalf("PauseTask failed: %v", err)
	}
	close(release)
	cancel1()
	dl1.Wait()

	pausedTask, err := st.GetTask(ctx, taskID)
	if err != nil || pausedTask.State != store.TaskPaused {
		t.Fatalf("expected PAUSED state, got %+v err=%v", pausedTask, err)
	}
	if pausedTask.BytesDone != 8192 {
		t.Fatalf("expected 8192 bytes paused on disk, got %d", pausedTask.BytesDone)
	}
	_ = st.Close()
	_ = fsStore.Close()

	// Simulated process restart.
	st2, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	fsStore2, err := localfs.Open(downloadDir)
	if err != nil {
		t.Fatal(err)
	}
	defer fsStore2.Close()

	var resumedOffset int64 = -1
	fake.SetOpenHook(func(exp backend.OpenExpectation, attempt int) (*backend.OpenedStream, error) {
		resumedOffset = exp.Offset
		return nil, nil
	})

	dl2 := NewService(st2, fsStore2, fake, fake, broker, Options{MaxConcurrent: 1, NoProgressTimeout: 10 * time.Second, MaxRetries: 3})
	if err := dl2.StartupRecover(ctx); err != nil {
		t.Fatalf("StartupRecover failed: %v", err)
	}
	if _, err := dl2.ResumeTask(ctx, taskID); err != nil {
		t.Fatalf("ResumeTask failed: %v", err)
	}

	runCtx2, cancel2 := context.WithCancel(ctx)
	defer cancel2()
	go dl2.RunLoop(runCtx2)

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		got, _ := st2.GetTask(ctx, taskID)
		if got.State == store.TaskCompleted {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	finalTask, err := st2.GetTask(ctx, taskID)
	if err != nil || finalTask.State != store.TaskCompleted {
		t.Fatalf("expected COMPLETED after resume, got %+v", finalTask)
	}
	if resumedOffset != 8192 {
		t.Fatalf("expected resume from exact offset 8192, got %d", resumedOffset)
	}
	delivered, err := os.ReadFile(filepath.Join(downloadDir, "Movies", "demo.mkv"))
	if err != nil || !bytes.Equal(delivered, payload) {
		t.Fatalf("delivered file mismatch: err=%v len=%d", err, len(delivered))
	}
}

func TestCommitCrashRecoveryAndDestinationConflict(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	downloadDir := t.TempDir()

	payload := []byte("committed-bytes-payload")
	sum := sha256.Sum256(payload)
	digest := hex.EncodeToString(sum[:])

	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	fsStore, err := localfs.Open(downloadDir)
	if err != nil {
		t.Fatal(err)
	}
	defer fsStore.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d1", "", "Docs")
	fake.PutFile("f1", "d1", "doc.bin", "GDOC", payload, time.Now())
	cat := catalog.NewService(st, fake, events.NewBroker(), "acct_1", 10*time.Minute, 5)
	src, _ := cat.CreateSource(ctx, catalog.CreateSourceInput{RemoteRootID: "d1", LocalAlias: "Docs", InitialMode: store.InitialModeDownloadExisting})
	pub, _ := cat.ScanSource(ctx, src.ID)
	task := pub.EnqueuedTasks[0]

	// Simulate crash right after localfs.CommitStaging but before store.CompleteCommit.
	claimed, err := st.ClaimNextTask(ctx, time.Now().Unix())
	if err != nil || claimed == nil {
		t.Fatal(err)
	}
	f, _, err := fsStore.OpenStagingForAppend(claimed.ID, true)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.Write(payload)
	_ = f.Close()
	if err := st.BeginCommit(ctx, claimed.ID, claimed.Attempt, digest, int64(len(payload))); err != nil {
		t.Fatal(err)
	}
	if err := fsStore.CommitStaging(claimed.ID, claimed.TargetRelPath); err != nil {
		t.Fatal(err)
	}

	dl := NewService(st, fsStore, fake, fake, events.NewBroker(), Options{MaxConcurrent: 1})
	if err := dl.StartupRecover(ctx); err != nil {
		t.Fatalf("StartupRecover failed: %v", err)
	}
	recovered, err := st.GetTask(ctx, task.ID)
	if err != nil || recovered.State != store.TaskCompleted {
		t.Fatalf("expected COMMITTING crash to recover to COMPLETED, got %+v err=%v", recovered, err)
	}

	// Verify cancel prevents scan re-enqueue and destination conflict never overwrites.
	fake.PutFile("f2", "d1", "existing.bin", "GEX", []byte("new-content"), time.Now())
	if err := os.WriteFile(filepath.Join(downloadDir, "Docs", "existing.bin"), []byte("user-owned!"), 0o644); err != nil {
		t.Fatal(err)
	}
	pub2, _ := cat.ScanSource(ctx, src.ID)
	task2 := pub2.EnqueuedTasks[0]

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	go dl.RunLoop(runCtx)

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		got, _ := st.GetTask(ctx, task2.ID)
		if got.State == store.TaskConflict {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	got2, _ := st.GetTask(ctx, task2.ID)
	if got2.State != store.TaskConflict {
		t.Fatalf("expected CONFLICT on existing destination file, got %s", got2.State)
	}
	existingBytes, _ := os.ReadFile(filepath.Join(downloadDir, "Docs", "existing.bin"))
	if string(existingBytes) != "user-owned!" {
		t.Fatalf("destination file was modified: %q", string(existingBytes))
	}

	if _, err := dl.CancelTask(ctx, task2.ID); err != nil {
		t.Fatalf("CancelTask on conflict failed: %v", err)
	}
	pub3, err := cat.ScanSource(ctx, src.ID)
	if err != nil || len(pub3.EnqueuedTasks) != 0 {
		t.Fatalf("canceled task version should not be re-enqueued: %+v err=%v", pub3.EnqueuedTasks, err)
	}
	_ = errors.New("")
}

func catRemote(fake *backend.FakeBackend) backend.Catalog {
	return fake
}
