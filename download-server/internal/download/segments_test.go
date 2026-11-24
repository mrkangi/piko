package download

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/store"
)

func TestPlanSegmentsProducesContiguousBalancedRanges(t *testing.T) {
	segments := planSegments(10, 3, 2)
	if len(segments) != 3 {
		t.Fatalf("expected 3 segments, got %+v", segments)
	}
	expected := [][2]int64{{0, 4}, {4, 7}, {7, 10}}
	for index, segment := range segments {
		if segment.Index != index || segment.StartOffset != expected[index][0] || segment.EndOffset != expected[index][1] {
			t.Fatalf("unexpected segment %d: %+v", index, segment)
		}
	}
	if !isMultiSegmentPlan(segments, 10) {
		t.Fatalf("planned segments should form a valid contiguous plan")
	}
	if planSegments(3, 4, 2) != nil {
		t.Fatalf("files below two minimum segments must stay single-stream")
	}
}

func TestSegmentedDownloadUsesParallelRangesAndDeliversExactBytes(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	const segmentCount = 4
	payload := bytes.Repeat([]byte("SEGMENTED-PIKPAK-DATA-"), 100000)
	total := int64(len(payload))
	fake := backend.NewFakeBackend()
	fake.PutDir("d_seg", "", "Segmented")
	fake.PutFile("f_seg", "d_seg", "movie.bin", "GCIDSEG", payload, time.Now())
	versionKey := "gcid:GCIDSEG:" + fmt.Sprint(total)

	var activeReaders atomic.Int32
	allReadersReady := make(chan struct{})
	releaseReaders := make(chan struct{})
	var readyOnce sync.Once
	var opensMu sync.Mutex
	opens := make(map[string]int)

	fake.SetOpenHook(func(exp backend.OpenExpectation, _ int) (*backend.OpenedStream, error) {
		if exp.Length <= 0 || exp.Offset < 0 || exp.Offset+exp.Length > total {
			return nil, &backend.Error{Kind: backend.ErrRangeInvalid, Message: "unexpected segmented range"}
		}
		opensMu.Lock()
		opens[fmt.Sprintf("%d-%d", exp.Offset, exp.Offset+exp.Length)]++
		opensMu.Unlock()
		reader := &parallelGateReader{
			data:   payload[exp.Offset : exp.Offset+exp.Length],
			active: &activeReaders,
			arrived: func() {
				if activeReaders.Load() >= segmentCount {
					readyOnce.Do(func() { close(allReadersReady) })
				}
			},
			release: releaseReaders,
		}
		return &backend.OpenedStream{
			Reader:          reader,
			Offset:          exp.Offset,
			TotalSize:       total,
			VerifiedVersion: versionKey,
			Verifiable:      true,
		}, nil
	})

	dataDir := t.TempDir()
	downloadDir := t.TempDir()
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

	broker := events.NewBroker()
	cat := catalog.NewService(st, fake, broker, "acct_1", 10*time.Minute, 5)
	source, err := cat.CreateSource(ctx, catalog.CreateSourceInput{
		RemoteRootID: "d_seg",
		LocalAlias:   "Segmented",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}
	published, err := cat.ScanSource(ctx, source.ID)
	if err != nil || len(published.EnqueuedTasks) != 1 {
		t.Fatalf("scan failed: %v tasks=%d", err, len(published.EnqueuedTasks))
	}
	task := published.EnqueuedTasks[0]

	dl := NewService(st, fsStore, fake, catRemote(fake), broker, Options{
		MaxConcurrent:     1,
		SegmentThreads:    segmentCount,
		SegmentMinBytes:   256 * 1024,
		NoProgressTimeout: 10 * time.Second,
		MaxRetries:        2,
	})
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	go dl.RunLoop(runCtx)

	select {
	case <-allReadersReady:
		close(releaseReaders)
	case <-ctx.Done():
		close(releaseReaders)
		t.Fatal("timed out waiting for four concurrent range readers")
	}

	completed := waitForTaskState(ctx, st, task.ID, store.TaskCompleted)
	if completed.State != store.TaskCompleted {
		t.Fatalf("expected COMPLETED, got %+v", completed)
	}
	delivered, err := os.ReadFile(filepath.Join(downloadDir, "Segmented", "movie.bin"))
	if err != nil || !bytes.Equal(delivered, payload) {
		t.Fatalf("delivered bytes mismatch: err=%v len=%d expected=%d", err, len(delivered), len(payload))
	}
	opensMu.Lock()
	openCount := len(opens)
	opensMu.Unlock()
	if openCount != segmentCount {
		t.Fatalf("expected %d distinct range opens, got %v", segmentCount, opens)
	}
	segments, err := st.ListSegments(ctx, task.ID)
	if err != nil || len(segments) != 0 {
		t.Fatalf("segment rows should be cleaned after commit: %+v err=%v", segments, err)
	}
}

func TestSegmentedDownloadFallsBackToSingleStream(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()

	payload := bytes.Repeat([]byte("FALLBACK-RANGE-"), 100000)
	fake := backend.NewFakeBackend()
	fake.PutDir("d_fb", "", "Fallback")
	fake.PutFile("f_fb", "d_fb", "movie.bin", "GCIDFB", payload, time.Now())
	fake.SetRangeSupported(false)

	dataDir := t.TempDir()
	downloadDir := t.TempDir()
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

	broker := events.NewBroker()
	cat := catalog.NewService(st, fake, broker, "acct_1", 10*time.Minute, 5)
	source, err := cat.CreateSource(ctx, catalog.CreateSourceInput{
		RemoteRootID: "d_fb",
		LocalAlias:   "Fallback",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}
	published, err := cat.ScanSource(ctx, source.ID)
	if err != nil || len(published.EnqueuedTasks) != 1 {
		t.Fatalf("scan failed: %v tasks=%d", err, len(published.EnqueuedTasks))
	}
	task := published.EnqueuedTasks[0]

	dl := NewService(st, fsStore, fake, catRemote(fake), broker, Options{
		MaxConcurrent:     1,
		SegmentThreads:    4,
		SegmentMinBytes:   256 * 1024,
		NoProgressTimeout: 10 * time.Second,
		MaxRetries:        2,
	})
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	go dl.RunLoop(runCtx)

	completed := waitForTaskState(ctx, st, task.ID, store.TaskCompleted)
	if completed.State != store.TaskCompleted {
		t.Fatalf("expected fallback download to complete, got %+v", completed)
	}
	if completed.Attempt != 1 {
		t.Fatalf("range fallback should not consume a retry, attempt=%d", completed.Attempt)
	}
	delivered, err := os.ReadFile(filepath.Join(downloadDir, "Fallback", "movie.bin"))
	if err != nil || !bytes.Equal(delivered, payload) {
		t.Fatalf("fallback delivered bytes mismatch: err=%v len=%d expected=%d", err, len(delivered), len(payload))
	}
}

func TestSegmentedPauseRestartResumesEachRangeOffset(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()

	const (
		segmentCount = 4
		gateBytes    = int64(64 * 1024)
	)
	payload := bytes.Repeat([]byte("SEGMENTED-RESUME-DATA-"), 100000)
	total := int64(len(payload))
	fake := backend.NewFakeBackend()
	fake.PutDir("d_resume", "", "Resume")
	fake.PutFile("f_resume", "d_resume", "movie.bin", "GCIDRESUME", payload, time.Now())
	versionKey := "gcid:GCIDRESUME:" + fmt.Sprint(total)

	var activeReaders atomic.Int32
	allReadersReady := make(chan struct{})
	releaseReaders := make(chan struct{})
	var readyOnce sync.Once
	fake.SetOpenHook(func(exp backend.OpenExpectation, _ int) (*backend.OpenedStream, error) {
		reader := &gateAfterBytesReader{
			data: payload[exp.Offset : exp.Offset+exp.Length],
			gate: gateBytes,
			onGate: func() {
				if activeReaders.Add(1) >= segmentCount {
					readyOnce.Do(func() { close(allReadersReady) })
				}
			},
			release: releaseReaders,
		}
		return &backend.OpenedStream{
			Reader:          reader,
			Offset:          exp.Offset,
			TotalSize:       total,
			VerifiedVersion: versionKey,
			Verifiable:      true,
		}, nil
	})

	dataDir := t.TempDir()
	downloadDir := t.TempDir()
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
	source, err := cat.CreateSource(ctx, catalog.CreateSourceInput{
		RemoteRootID: "d_resume",
		LocalAlias:   "Resume",
		InitialMode:  store.InitialModeDownloadExisting,
	})
	if err != nil {
		t.Fatal(err)
	}
	published, err := cat.ScanSource(ctx, source.ID)
	if err != nil || len(published.EnqueuedTasks) != 1 {
		t.Fatalf("scan failed: %v tasks=%d", err, len(published.EnqueuedTasks))
	}
	task := published.EnqueuedTasks[0]

	dl1 := NewService(st, fsStore, fake, catRemote(fake), broker, Options{
		MaxConcurrent:     1,
		SegmentThreads:    segmentCount,
		SegmentMinBytes:   256 * 1024,
		NoProgressTimeout: 10 * time.Second,
		MaxRetries:        2,
	})
	runCtx1, stop1 := context.WithCancel(ctx)
	go dl1.RunLoop(runCtx1)

	select {
	case <-allReadersReady:
	case <-ctx.Done():
		close(releaseReaders)
		t.Fatal("timed out waiting for all segmented readers")
	}
	if _, err := dl1.PauseTask(ctx, task.ID); err != nil {
		close(releaseReaders)
		t.Fatalf("PauseTask failed: %v", err)
	}
	close(releaseReaders)
	paused := waitForTaskState(ctx, st, task.ID, store.TaskPaused)
	stop1()
	dl1.Wait()
	if paused.State != store.TaskPaused {
		t.Fatalf("expected PAUSED, got %+v", paused)
	}
	if paused.BytesDone != segmentCount*gateBytes {
		t.Fatalf("expected %d bytes persisted across segments, got %d", segmentCount*gateBytes, paused.BytesDone)
	}
	segments, err := st.ListSegments(ctx, task.ID)
	if err != nil || len(segments) != segmentCount {
		t.Fatalf("expected persisted segment plan, got %+v err=%v", segments, err)
	}
	expectedOffsets := make(map[int64]struct{}, segmentCount)
	for _, segment := range segments {
		if segment.DoneOffset != segment.StartOffset+gateBytes {
			t.Fatalf("unexpected persisted segment progress: %+v", segment)
		}
		expectedOffsets[segment.DoneOffset] = struct{}{}
	}

	_ = fsStore.Close()
	_ = st.Close()

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

	var opensMu sync.Mutex
	resumedOffsets := make(map[int64]struct{})
	fake.SetOpenHook(func(exp backend.OpenExpectation, _ int) (*backend.OpenedStream, error) {
		opensMu.Lock()
		resumedOffsets[exp.Offset] = struct{}{}
		opensMu.Unlock()
		return &backend.OpenedStream{
			Reader:          io.NopCloser(bytes.NewReader(payload[exp.Offset : exp.Offset+exp.Length])),
			Offset:          exp.Offset,
			TotalSize:       total,
			VerifiedVersion: versionKey,
			Verifiable:      true,
		}, nil
	})

	dl2 := NewService(st2, fsStore2, fake, catRemote(fake), broker, Options{
		MaxConcurrent:     1,
		SegmentThreads:    segmentCount,
		SegmentMinBytes:   256 * 1024,
		NoProgressTimeout: 10 * time.Second,
		MaxRetries:        2,
	})
	if err := dl2.StartupRecover(ctx); err != nil {
		t.Fatalf("StartupRecover failed: %v", err)
	}
	if _, err := dl2.ResumeTask(ctx, task.ID); err != nil {
		t.Fatalf("ResumeTask failed: %v", err)
	}
	runCtx2, stop2 := context.WithCancel(ctx)
	defer stop2()
	go dl2.RunLoop(runCtx2)

	completed := waitForTaskState(ctx, st2, task.ID, store.TaskCompleted)
	if completed.State != store.TaskCompleted {
		t.Fatalf("expected resumed segmented task to complete, got %+v", completed)
	}
	if completed.Attempt != 1 {
		t.Fatalf("pause/resume should not consume retry budget, attempt=%d", completed.Attempt)
	}
	opensMu.Lock()
	for offset := range expectedOffsets {
		if _, ok := resumedOffsets[offset]; !ok {
			opensMu.Unlock()
			t.Fatalf("resume did not restart from persisted offset %d: %v", offset, resumedOffsets)
		}
	}
	opensMu.Unlock()
	delivered, err := os.ReadFile(filepath.Join(downloadDir, "Resume", "movie.bin"))
	if err != nil || !bytes.Equal(delivered, payload) {
		t.Fatalf("resumed delivered bytes mismatch: err=%v len=%d expected=%d", err, len(delivered), len(payload))
	}
}

func waitForTaskState(ctx context.Context, st *store.Store, taskID string, wanted string) store.DownloadTask {
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	var latest store.DownloadTask
	for {
		task, err := st.GetTask(ctx, taskID)
		if err == nil {
			latest = task
			if task.State == wanted || task.State == store.TaskFailed || task.State == store.TaskConflict {
				return task
			}
		}
		select {
		case <-ctx.Done():
			return latest
		case <-ticker.C:
		}
	}
}

type parallelGateReader struct {
	data    []byte
	pos     int
	active  *atomic.Int32
	arrived func()
	release chan struct{}
	once    sync.Once
}

func (reader *parallelGateReader) Read(p []byte) (int, error) {
	reader.once.Do(func() {
		reader.active.Add(1)
		reader.arrived()
		<-reader.release
	})
	if reader.pos >= len(reader.data) {
		return 0, io.EOF
	}
	n := copy(p, reader.data[reader.pos:])
	reader.pos += n
	return n, nil
}

func (reader *parallelGateReader) Close() error {
	return nil
}

type gateAfterBytesReader struct {
	data    []byte
	pos     int
	gate    int64
	onGate  func()
	release chan struct{}
	gated   bool
}

func (reader *gateAfterBytesReader) Read(p []byte) (int, error) {
	if reader.pos >= len(reader.data) {
		return 0, io.EOF
	}
	if !reader.gated && int64(reader.pos) >= reader.gate {
		reader.gated = true
		reader.onGate()
		<-reader.release
		return 0, context.Canceled
	}
	end := reader.pos + len(p)
	if end > len(reader.data) {
		end = len(reader.data)
	}
	if !reader.gated && int64(end) > reader.gate {
		end = reader.pos + int(reader.gate-int64(reader.pos))
	}
	n := copy(p, reader.data[reader.pos:end])
	reader.pos += n
	return n, nil
}

func (reader *gateAfterBytesReader) Close() error {
	return nil
}
