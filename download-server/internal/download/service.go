package download

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/store"
)

type Options struct {
	MaxConcurrent     int
	MinFreeSpaceBytes int64
	NoProgressTimeout time.Duration
	MaxRetries        int
}

type TaskView struct {
	store.DownloadTask
	SpeedBytesPerSec int64   `json:"speed_bytes_per_sec"`
	ETASeconds       int64   `json:"eta_seconds"`
	ProgressPercent  float64 `json:"progress_percent"`
}

type SystemStats struct {
	ActiveWorkers   int    `json:"active_workers"`
	QueuedCount     int    `json:"queued_count"`
	PausedCount     int    `json:"paused_count"`
	FailedCount     int    `json:"failed_count"`
	CompletedCount  int    `json:"completed_count"`
	TotalSpeedBytes int64  `json:"total_speed_bytes"`
	FreeSpaceBytes  uint64 `json:"free_space_bytes"`
	LowDiskBlocked  bool   `json:"low_disk_blocked"`
}

type speedSample struct {
	at    time.Time
	bytes int64
}

type activeAttempt struct {
	taskID       string
	attempt      int
	cancel       context.CancelFunc
	mu           sync.Mutex
	bytesDone    int64
	bytesTotal   int64
	sessionBytes int64
	samples      []speedSample
	speed        int64
	etaSeconds   int64
}

type Service struct {
	store   *store.Store
	localfs *localfs.Store
	content backend.ContentSource
	catalog backend.Catalog
	broker  *events.Broker
	opts    Options

	mu             sync.Mutex
	dispatchMu     sync.Mutex
	fsMu           sync.RWMutex
	active         map[string]*activeAttempt
	lowDiskBlocked bool
	wakeCh         chan struct{}
	wg             sync.WaitGroup
}

func NewService(
	st *store.Store,
	fsStore *localfs.Store,
	content backend.ContentSource,
	catalog backend.Catalog,
	broker *events.Broker,
	opts Options,
) *Service {
	if opts.MaxConcurrent < 1 {
		opts.MaxConcurrent = 2
	}
	if opts.NoProgressTimeout < 5*time.Second {
		opts.NoProgressTimeout = 5 * time.Minute
	}
	if opts.MaxRetries < 0 {
		opts.MaxRetries = 5
	}
	return &Service{
		store:   st,
		localfs: fsStore,
		content: content,
		catalog: catalog,
		broker:  broker,
		opts:    opts,
		active:  make(map[string]*activeAttempt),
		wakeCh:  make(chan struct{}, 1),
	}
}

func (svc *Service) Signal() {
	select {
	case svc.wakeCh <- struct{}{}:
	default:
	}
}

func (svc *Service) fsStore() *localfs.Store {
	svc.fsMu.RLock()
	defer svc.fsMu.RUnlock()
	return svc.localfs
}

func (svc *Service) DownloadDir() string {
	return svc.fsStore().RootPath()
}

func (svc *Service) CurrentOptions() Options {
	svc.mu.Lock()
	defer svc.mu.Unlock()
	return svc.opts
}

func (svc *Service) UpdateOptions(maxConcurrent int, minFreeSpaceBytes int64) {
	svc.mu.Lock()
	if maxConcurrent >= 1 && maxConcurrent <= 16 {
		svc.opts.MaxConcurrent = maxConcurrent
	}
	if minFreeSpaceBytes >= 0 {
		svc.opts.MinFreeSpaceBytes = minFreeSpaceBytes
	}
	svc.mu.Unlock()
	svc.Signal()
}

func (svc *Service) SwitchDownloadDir(ctx context.Context, newDir string) (string, error) {
	svc.dispatchMu.Lock()
	defer svc.dispatchMu.Unlock()

	svc.mu.Lock()
	activeCount := len(svc.active)
	svc.mu.Unlock()
	if activeCount > 0 {
		return "", store.ErrConflict
	}
	hasActive, err := svc.store.HasActiveTasks(ctx)
	if err != nil {
		return "", err
	}
	if hasActive {
		return "", store.ErrConflict
	}

	newFS, err := localfs.Open(newDir)
	if err != nil {
		return "", err
	}
	if err := newFS.Probe(); err != nil {
		_ = newFS.Close()
		return "", fmt.Errorf("download volume probe failed: %w", err)
	}
	if newFS.RootPath() == svc.fsStore().RootPath() {
		_ = newFS.Close()
		return svc.fsStore().RootPath(), nil
	}

	svc.fsMu.Lock()
	oldFS := svc.localfs
	svc.localfs = newFS
	svc.fsMu.Unlock()
	if oldFS != nil {
		_ = oldFS.Close()
	}
	svc.Signal()
	return newFS.RootPath(), nil
}

func (svc *Service) StartupRecover(ctx context.Context) error {
	if err := svc.store.AbortStaleScanRuns(ctx); err != nil {
		return err
	}
	tasks, err := svc.store.ListNonTerminalTasks(ctx)
	if err != nil {
		return err
	}
	for _, task := range tasks {
		if err := svc.recoverOne(ctx, task); err != nil {
			return err
		}
	}
	return nil
}

func (svc *Service) recoverOne(ctx context.Context, task store.DownloadTask) error {
	fsStore := svc.fsStore()
	stagingSize, stagingExists, statErr := fsStore.StagingSize(task.ID)
	if statErr != nil && !errors.Is(statErr, localfs.ErrSymlinkBlocked) {
		return statErr
	}
	reconciledBytes := int64(0)
	if task.StrongVersion && stagingExists && stagingSize >= 0 {
		reconciledBytes = stagingSize
	}

	switch task.State {
	case store.TaskPausing:
		return svc.store.RecoverTaskState(ctx, task.ID, store.TaskPaused, store.DesiredNone, reconciledBytes, "", "")
	case store.TaskCanceling:
		_ = fsStore.RemoveStaging(task.ID)
		return svc.store.RecoverTaskState(ctx, task.ID, store.TaskCanceled, store.DesiredCancel, 0, "", "")
	case store.TaskCommitting:
		verified, err := fsStore.VerifyCommittedFile(task.ID, task.TargetRelPath, task.BytesTotal, task.CommitSHA256)
		if err == nil && verified {
			if compErr := svc.store.CompleteCommit(ctx, task.ID, 0, store.Delivery{
				SourceID:           task.SourceID,
				RemoteID:           task.RemoteID,
				VersionKey:         task.VersionKey,
				LocalRelPath:       task.TargetRelPath,
				Size:               task.BytesTotal,
				LocalSHA256:        task.CommitSHA256,
				RemoteGCID:         task.GCID,
				VerifiedRemoteHash: false,
			}); compErr != nil {
				return svc.store.RecoverTaskState(ctx, task.ID, store.TaskConflict, store.DesiredNone, task.BytesTotal, "commit_recovery_failed", compErr.Error())
			}
			_ = fsStore.RemoveStaging(task.ID)
			return nil
		}
		exists, _, _ := fsStore.DestinationExists(task.TargetRelPath)
		if exists {
			return svc.store.RecoverTaskState(ctx, task.ID, store.TaskConflict, store.DesiredNone, reconciledBytes, "conflict", "destination path occupied with mismatched content")
		}
		if stagingExists && stagingSize == task.BytesTotal && task.CommitSHA256 != "" {
			_, digest, digErr := fsStore.ComputeStagingDigest(task.ID)
			if digErr == nil && digest == task.CommitSHA256 {
				if commitErr := fsStore.CommitStaging(task.ID, task.TargetRelPath); commitErr == nil {
					if compErr := svc.store.CompleteCommit(ctx, task.ID, 0, store.Delivery{
						SourceID:     task.SourceID,
						RemoteID:     task.RemoteID,
						VersionKey:   task.VersionKey,
						LocalRelPath: task.TargetRelPath,
						Size:         task.BytesTotal,
						LocalSHA256:  digest,
						RemoteGCID:   task.GCID,
					}); compErr == nil {
						_ = fsStore.RemoveStaging(task.ID)
						return nil
					}
				}
			}
		}
		return svc.store.RecoverTaskState(ctx, task.ID, store.TaskFailed, store.DesiredNone, reconciledBytes, "commit_interrupted", "commit interrupted without verifiable staging payload")
	case store.TaskRunning, store.TaskVerifying:
		if task.DesiredAction == store.DesiredCancel {
			_ = fsStore.RemoveStaging(task.ID)
			return svc.store.RecoverTaskState(ctx, task.ID, store.TaskCanceled, store.DesiredCancel, 0, "", "")
		}
		if task.DesiredAction == store.DesiredPause {
			return svc.store.RecoverTaskState(ctx, task.ID, store.TaskPaused, store.DesiredNone, reconciledBytes, "", "")
		}
		return svc.store.RecoverTaskState(ctx, task.ID, store.TaskQueued, store.DesiredNone, reconciledBytes, "", "")
	default:
		if reconciledBytes != task.BytesDone {
			return svc.store.RecoverTaskState(ctx, task.ID, task.State, task.DesiredAction, reconciledBytes, task.ErrorCode, task.ErrorMessage)
		}
		return nil
	}
}

func (svc *Service) RunLoop(ctx context.Context) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		svc.dispatchAvailable(ctx)
		select {
		case <-ctx.Done():
			svc.wg.Wait()
			return
		case <-svc.wakeCh:
		case <-ticker.C:
		}
	}
}

func (svc *Service) Wait() {
	svc.wg.Wait()
}

func (svc *Service) dispatchAvailable(ctx context.Context) {
	if !backend.IsReady(svc.content) || !backend.IsReady(svc.catalog) {
		return
	}
	svc.dispatchMu.Lock()
	defer svc.dispatchMu.Unlock()

	fsStore := svc.fsStore()
	for {
		if ctx.Err() != nil {
			return
		}
		svc.mu.Lock()
		activeCount := len(svc.active)
		opts := svc.opts
		svc.mu.Unlock()
		if activeCount >= opts.MaxConcurrent {
			return
		}

		if opts.MinFreeSpaceBytes > 0 {
			freeBytes, err := fsStore.FreeBytes()
			if err == nil && freeBytes < uint64(opts.MinFreeSpaceBytes) {
				svc.mu.Lock()
				svc.lowDiskBlocked = true
				svc.mu.Unlock()
				return
			}
			svc.mu.Lock()
			svc.lowDiskBlocked = false
			svc.mu.Unlock()
		}

		task, err := svc.store.ClaimNextTask(ctx, time.Now().Unix())
		if err != nil || task == nil {
			return
		}

		attemptCtx, cancel := context.WithCancel(ctx)
		state := &activeAttempt{
			taskID:     task.ID,
			attempt:    task.Attempt,
			cancel:     cancel,
			bytesDone:  task.BytesDone,
			bytesTotal: task.BytesTotal,
			etaSeconds: -1,
		}
		svc.mu.Lock()
		svc.active[task.ID] = state
		svc.mu.Unlock()

		svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})

		svc.wg.Add(1)
		go func(claimed store.DownloadTask, act *activeAttempt, runCtx context.Context) {
			defer svc.wg.Done()
			defer func() {
				act.cancel()
				svc.mu.Lock()
				delete(svc.active, claimed.ID)
				svc.mu.Unlock()
				svc.broker.Publish(events.Event{Type: "task.updated", SourceID: claimed.SourceID, TaskID: claimed.ID})
				svc.Signal()
			}()
			svc.executeAttempt(runCtx, claimed, act)
		}(*task, state, attemptCtx)
	}
}

func (svc *Service) executeAttempt(ctx context.Context, task store.DownloadTask, act *activeAttempt) {
	fsStore := svc.fsStore()
	exists, _, err := fsStore.DestinationExists(task.TargetRelPath)
	if err != nil {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, task.BytesDone, store.TaskFailed, "filesystem_error", err.Error(), 0)
		return
	}
	if exists {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, task.BytesDone, store.TaskConflict, "conflict", "destination path already exists", 0)
		return
	}

	entry, err := svc.catalog.Stat(ctx, task.RemoteID)
	if err != nil {
		svc.handleAttemptError(task, act, task.BytesDone, err)
		return
	}
	if entry.IsDir || entry.VersionKey() != task.VersionKey {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, task.BytesDone, store.TaskFailed, string(backend.ErrVersionMismatch), "remote file version changed before download", 0)
		return
	}

	stagingSize, stagingExists, err := fsStore.StagingSize(task.ID)
	if err != nil {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, 0, store.TaskFailed, "staging_error", err.Error(), 0)
		return
	}

	offset := int64(0)
	truncate := true
	if stagingExists && stagingSize > 0 && task.StrongVersion && entry.HasStrongVersion() && stagingSize <= entry.Size {
		offset = stagingSize
		truncate = false
	}

	act.setTotals(offset, entry.Size, 0)

	if offset < entry.Size {
		stream, openedOffset, err := svc.openWithRangeFallback(ctx, task, entry, offset)
		if err != nil {
			svc.handleAttemptError(task, act, offset, err)
			return
		}
		if openedOffset == 0 && offset > 0 {
			offset = 0
			truncate = true
			act.setTotals(0, entry.Size, 0)
		}

		writtenTotal, streamErr := svc.copyStreamToStaging(ctx, fsStore, task, act, stream, offset, truncate, entry.Size)
		if streamErr != nil {
			svc.handleAttemptError(task, act, writtenTotal, streamErr)
			return
		}
	}

	if err := svc.store.TransitionToVerifying(ctx, task.ID, task.Attempt, entry.Size); err != nil {
		svc.handleStopIntentOrError(task, entry.Size, err)
		return
	}

	verifiedSize, sha256Hex, err := fsStore.ComputeStagingDigest(task.ID)
	if err != nil {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, entry.Size, store.TaskFailed, "verify_error", err.Error(), 0)
		return
	}
	if verifiedSize != entry.Size {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, verifiedSize, store.TaskRetryWait, "size_mismatch", "staging size differs from expected total", nextBackoffAt(task.Attempt, 0))
		return
	}

	if err := svc.store.BeginCommit(context.Background(), task.ID, task.Attempt, sha256Hex, entry.Size); err != nil {
		svc.handleStopIntentOrError(task, entry.Size, err)
		return
	}

	if err := fsStore.CommitStaging(task.ID, task.TargetRelPath); err != nil {
		if errors.Is(err, localfs.ErrConflict) {
			_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, entry.Size, store.TaskConflict, "conflict", "destination path occupied during commit", 0)
			return
		}
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, entry.Size, store.TaskFailed, "commit_error", err.Error(), 0)
		return
	}

	if err := svc.store.CompleteCommit(context.Background(), task.ID, task.Attempt, store.Delivery{
		SourceID:           task.SourceID,
		RemoteID:           task.RemoteID,
		VersionKey:         task.VersionKey,
		LocalRelPath:       task.TargetRelPath,
		Size:               entry.Size,
		LocalSHA256:        sha256Hex,
		RemoteGCID:         entry.GCID,
		VerifiedRemoteHash: false,
	}); err != nil {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, entry.Size, store.TaskConflict, "delivery_record_error", err.Error(), 0)
		return
	}
	_ = fsStore.RemoveStaging(task.ID)
}

func (svc *Service) openWithRangeFallback(ctx context.Context, task store.DownloadTask, entry backend.Entry, offset int64) (*backend.OpenedStream, int64, error) {
	stream, err := svc.content.OpenOriginal(ctx, backend.OpenExpectation{
		ObjectID:           task.RemoteID,
		ExpectedVersionKey: task.VersionKey,
		ExpectedGCID:       task.GCID,
		ExpectedSize:       entry.Size,
		Offset:             offset,
	})
	if err == nil {
		return stream, offset, nil
	}
	if offset > 0 && backend.KindOf(err) == backend.ErrRangeNotSupported {
		fresh, freshErr := svc.content.OpenOriginal(ctx, backend.OpenExpectation{
			ObjectID:           task.RemoteID,
			ExpectedVersionKey: task.VersionKey,
			ExpectedGCID:       task.GCID,
			ExpectedSize:       entry.Size,
			Offset:             0,
		})
		if freshErr != nil {
			return nil, 0, freshErr
		}
		return fresh, 0, nil
	}
	return nil, offset, err
}

func (svc *Service) copyStreamToStaging(
	ctx context.Context,
	fsStore *localfs.Store,
	task store.DownloadTask,
	act *activeAttempt,
	stream *backend.OpenedStream,
	offset int64,
	truncate bool,
	expectedSize int64,
) (int64, error) {
	defer stream.Reader.Close()

	file, actualOffset, err := fsStore.OpenStagingForAppend(task.ID, truncate)
	if err != nil {
		return offset, err
	}
	defer file.Close()

	if actualOffset != offset {
		return actualOffset, fmt.Errorf("staging offset changed unexpectedly: got %d expected %d", actualOffset, offset)
	}

	buf := make([]byte, 128*1024)
	writtenTotal := offset
	sessionBytes := int64(0)
	lastDBFlush := time.Now()
	lastDBBytes := writtenTotal
	lastUIEmit := time.Now()

	for {
		if err := ctx.Err(); err != nil {
			_ = file.Sync()
			return writtenTotal, err
		}

		n, readErr := readWithWatchdog(ctx, stream.Reader, buf, svc.opts.NoProgressTimeout)
		if n > 0 {
			if writtenTotal+int64(n) > expectedSize {
				_ = file.Sync()
				return writtenTotal, &backend.Error{Kind: backend.ErrProtocol, Message: "stream exceeded expected file size"}
			}
			wn, writeErr := file.Write(buf[:n])
			if writeErr != nil {
				_ = file.Sync()
				return writtenTotal, writeErr
			}
			if wn != n {
				_ = file.Sync()
				return writtenTotal, io.ErrShortWrite
			}
			writtenTotal += int64(wn)
			sessionBytes += int64(wn)
			act.recordProgress(writtenTotal, expectedSize, sessionBytes)

			now := time.Now()
			if now.Sub(lastUIEmit) >= time.Second {
				svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
				lastUIEmit = now
			}
			if now.Sub(lastDBFlush) >= 5*time.Second || writtenTotal-lastDBBytes >= 16*1024*1024 {
				_ = svc.store.UpdateAttemptProgress(context.Background(), task.ID, task.Attempt, writtenTotal, expectedSize)
				lastDBFlush = now
				lastDBBytes = writtenTotal
			}
		}
		if readErr != nil {
			_ = file.Sync()
			if errors.Is(readErr, io.EOF) {
				if writtenTotal != expectedSize {
					return writtenTotal, &backend.Error{Kind: backend.ErrTransient, Message: fmt.Sprintf("stream ended early at %d/%d", writtenTotal, expectedSize)}
				}
				return writtenTotal, nil
			}
			return writtenTotal, readErr
		}
	}
}

func readWithWatchdog(ctx context.Context, reader io.Reader, buf []byte, timeout time.Duration) (int, error) {
	type readResult struct {
		n   int
		err error
	}
	resCh := make(chan readResult, 1)
	go func() {
		n, err := reader.Read(buf)
		resCh <- readResult{n: n, err: err}
	}()
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		if closer, ok := reader.(io.Closer); ok {
			_ = closer.Close()
		}
		return 0, ctx.Err()
	case <-timer.C:
		if closer, ok := reader.(io.Closer); ok {
			_ = closer.Close()
		}
		return 0, &backend.Error{Kind: backend.ErrTransient, Message: "no progress timeout while reading stream"}
	case res := <-resCh:
		return res.n, res.err
	}
}

func (svc *Service) handleStopIntentOrError(task store.DownloadTask, bytesDone int64, err error) {
	if errors.Is(err, store.ErrStoppedByIntent) {
		updated, _ := svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, bytesDone, store.TaskPaused, "", "", 0)
		if updated.State == store.TaskCanceled {
			_ = svc.fsStore().RemoveStaging(task.ID)
		}
		return
	}
	svc.handleAttemptError(task, nil, bytesDone, err)
}

func (svc *Service) handleAttemptError(task store.DownloadTask, _ *activeAttempt, bytesDone int64, err error) {
	latest, loadErr := svc.store.GetTask(context.Background(), task.ID)
	if loadErr == nil {
		if latest.DesiredAction == store.DesiredCancel {
			_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, 0, store.TaskCanceled, "", "", 0)
			_ = svc.fsStore().RemoveStaging(task.ID)
			return
		}
		if latest.DesiredAction == store.DesiredPause {
			_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, bytesDone, store.TaskPaused, "", "", 0)
			return
		}
	}

	if errors.Is(err, context.Canceled) {
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, bytesDone, store.TaskPaused, "", "", 0)
		return
	}

	errCode := string(backend.KindOf(err))
	if errCode == "" {
		errCode = "transfer_error"
	}
	retryable := backend.IsRetryable(err) || errCode == "transfer_error"
	if retryable && task.Attempt < task.MaxAttempts {
		var retryAfter time.Duration
		var bErr *backend.Error
		if errors.As(err, &bErr) {
			retryAfter = bErr.RetryAfter
		}
		nextAt := nextBackoffAt(task.Attempt, retryAfter)
		_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, bytesDone, store.TaskRetryWait, errCode, err.Error(), nextAt)
		return
	}
	_, _ = svc.store.FinalizeAttemptStop(context.Background(), task.ID, task.Attempt, bytesDone, store.TaskFailed, errCode, err.Error(), 0)
}

func nextBackoffAt(attempt int, retryAfter time.Duration) int64 {
	if retryAfter > 0 {
		return time.Now().Add(retryAfter).Unix()
	}
	if attempt < 1 {
		attempt = 1
	}
	if attempt > 6 {
		attempt = 6
	}
	base := 5 * time.Second * time.Duration(1<<(attempt-1))
	if base > 5*time.Minute {
		base = 5 * time.Minute
	}
	jitter := time.Duration(rand.Int64N(int64(base/4) + 1))
	return time.Now().Add(base + jitter).Unix()
}

func (svc *Service) PauseTask(ctx context.Context, taskID string) (store.DownloadTask, error) {
	task, err := svc.store.RequestPause(ctx, taskID)
	if err != nil {
		return store.DownloadTask{}, err
	}
	svc.cancelActive(taskID)
	svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
	return task, nil
}

func (svc *Service) ResumeTask(ctx context.Context, taskID string) (store.DownloadTask, error) {
	task, err := svc.store.RequestResume(ctx, taskID)
	if err != nil {
		return store.DownloadTask{}, err
	}
	svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
	svc.Signal()
	return task, nil
}

func (svc *Service) CancelTask(ctx context.Context, taskID string) (store.DownloadTask, error) {
	task, err := svc.store.RequestCancel(ctx, taskID)
	if err != nil {
		return store.DownloadTask{}, err
	}
	svc.cancelActive(taskID)
	if task.State == store.TaskCanceled {
		_ = svc.fsStore().RemoveStaging(task.ID)
	}
	svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
	return task, nil
}

func (svc *Service) RetryTask(ctx context.Context, taskID string) (store.DownloadTask, error) {
	task, err := svc.store.RequestRetry(ctx, taskID)
	if err != nil {
		return store.DownloadTask{}, err
	}
	svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
	svc.Signal()
	return task, nil
}

func (svc *Service) BatchAction(ctx context.Context, action string, sourceID string) (int, error) {
	tasks, err := svc.store.ListTasks(ctx, "", sourceID, 500, 0)
	if err != nil {
		return 0, err
	}
	affected := 0
	for _, task := range tasks {
		switch action {
		case "pause":
			switch task.State {
			case store.TaskQueued, store.TaskRunning, store.TaskRetryWait, store.TaskVerifying:
				if _, err := svc.PauseTask(ctx, task.ID); err == nil {
					affected++
				}
			}
		case "resume":
			if task.State == store.TaskPaused {
				if _, err := svc.ResumeTask(ctx, task.ID); err == nil {
					affected++
				}
			}
		case "retry":
			switch task.State {
			case store.TaskFailed, store.TaskCanceled, store.TaskConflict:
				if _, err := svc.RetryTask(ctx, task.ID); err == nil {
					affected++
				}
			}
		default:
			return 0, fmt.Errorf("unsupported batch action: %s", action)
		}
	}
	return affected, nil
}

func (svc *Service) cancelActive(taskID string) {
	svc.mu.Lock()
	act := svc.active[taskID]
	svc.mu.Unlock()
	if act != nil {
		act.cancel()
	}
}

func (svc *Service) ListTaskViews(ctx context.Context, stateFilter string, sourceID string, limit int, offset int) ([]TaskView, error) {
	tasks, err := svc.store.ListTasks(ctx, stateFilter, sourceID, limit, offset)
	if err != nil {
		return nil, err
	}
	svc.mu.Lock()
	activeCopy := make(map[string]*activeAttempt, len(svc.active))
	for k, v := range svc.active {
		activeCopy[k] = v
	}
	svc.mu.Unlock()

	views := make([]TaskView, 0, len(tasks))
	for _, task := range tasks {
		view := TaskView{
			DownloadTask: task,
			ETASeconds:   -1,
		}
		if act, ok := activeCopy[task.ID]; ok {
			done, total, speed, eta := act.snapshot()
			view.BytesDone = done
			if total > 0 {
				view.BytesTotal = total
			}
			view.SpeedBytesPerSec = speed
			view.ETASeconds = eta
		}
		if view.BytesTotal > 0 {
			pct := float64(view.BytesDone) * 100.0 / float64(view.BytesTotal)
			if pct > 100 {
				pct = 100
			}
			view.ProgressPercent = pct
		}
		views = append(views, view)
	}
	return views, nil
}

func (svc *Service) Stats(ctx context.Context) (SystemStats, error) {
	tasks, err := svc.store.ListTasks(ctx, "", "", 500, 0)
	if err != nil {
		return SystemStats{}, err
	}
	stats := SystemStats{}
	for _, task := range tasks {
		switch task.State {
		case store.TaskQueued, store.TaskRetryWait:
			stats.QueuedCount++
		case store.TaskPaused, store.TaskPausing:
			stats.PausedCount++
		case store.TaskFailed, store.TaskConflict:
			stats.FailedCount++
		case store.TaskCompleted:
			stats.CompletedCount++
		}
	}
	svc.mu.Lock()
	stats.ActiveWorkers = len(svc.active)
	stats.LowDiskBlocked = svc.lowDiskBlocked
	for _, act := range svc.active {
		_, _, speed, _ := act.snapshot()
		stats.TotalSpeedBytes += speed
	}
	svc.mu.Unlock()

	if free, err := svc.fsStore().FreeBytes(); err == nil {
		stats.FreeSpaceBytes = free
	}
	return stats, nil
}

func (act *activeAttempt) setTotals(done int64, total int64, sessionBytes int64) {
	act.mu.Lock()
	defer act.mu.Unlock()
	act.bytesDone = done
	act.bytesTotal = total
	act.sessionBytes = sessionBytes
	act.samples = []speedSample{{at: time.Now(), bytes: sessionBytes}}
	act.speed = 0
	act.etaSeconds = -1
}

func (act *activeAttempt) recordProgress(done int64, total int64, sessionBytes int64) {
	act.mu.Lock()
	defer act.mu.Unlock()
	now := time.Now()
	act.bytesDone = done
	act.bytesTotal = total
	act.sessionBytes = sessionBytes
	act.samples = append(act.samples, speedSample{at: now, bytes: sessionBytes})
	cutoff := now.Add(-8 * time.Second)
	trimIdx := 0
	for trimIdx < len(act.samples)-2 && act.samples[trimIdx].at.Before(cutoff) {
		trimIdx++
	}
	if trimIdx > 0 {
		act.samples = act.samples[trimIdx:]
	}
	if len(act.samples) >= 2 {
		first := act.samples[0]
		elapsed := now.Sub(first.at).Seconds()
		delta := sessionBytes - first.bytes
		if elapsed >= 0.2 && delta >= 0 {
			act.speed = int64(float64(delta) / elapsed)
			if act.speed > 0 && total > done {
				act.etaSeconds = (total - done) / act.speed
			} else {
				act.etaSeconds = -1
			}
		}
	}
}

func (act *activeAttempt) snapshot() (int64, int64, int64, int64) {
	act.mu.Lock()
	defer act.mu.Unlock()
	return act.bytesDone, act.bytesTotal, act.speed, act.etaSeconds
}
