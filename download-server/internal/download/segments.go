package download

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/store"
)

const (
	segmentBufferBytes           = 128 * 1024
	maxSegmentConnectionAttempts = 3
)

var (
	errRangeUnsupported      = errors.New("download node does not support range requests")
	errSingleStreamRequested = errors.New("single stream requested by settings")
)

func planSegments(total int64, threads int, minSegmentBytes int64) []store.DownloadSegment {
	if total <= 0 || threads < 2 || minSegmentBytes <= 0 {
		return nil
	}
	maxSegments := total / minSegmentBytes
	if maxSegments < 2 {
		return nil
	}
	count := int64(threads)
	if count > maxSegments {
		count = maxSegments
	}
	if count < 2 {
		return nil
	}

	baseSize := total / count
	remainder := total % count
	segments := make([]store.DownloadSegment, 0, count)
	start := int64(0)
	for index := int64(0); index < count; index++ {
		size := baseSize
		if index < remainder {
			size++
		}
		end := start + size
		segments = append(segments, store.DownloadSegment{
			Index:       int(index),
			StartOffset: start,
			EndOffset:   end,
			DoneOffset:  start,
		})
		start = end
	}
	return segments
}

func isSingleSegmentMarker(segments []store.DownloadSegment, total int64) bool {
	return len(segments) == 1 &&
		segments[0].Index == 0 &&
		segments[0].StartOffset == 0 &&
		segments[0].EndOffset == total
}

func isMultiSegmentPlan(segments []store.DownloadSegment, total int64) bool {
	if len(segments) < 2 || total <= 0 {
		return false
	}
	next := int64(0)
	for index, segment := range segments {
		if segment.Index != index || segment.StartOffset != next || segment.EndOffset <= segment.StartOffset {
			return false
		}
		if segment.DoneOffset < segment.StartOffset || segment.DoneOffset > segment.EndOffset {
			return false
		}
		next = segment.EndOffset
	}
	return next == total
}

func segmentDoneTotal(segments []store.DownloadSegment) int64 {
	var done int64
	for _, segment := range segments {
		done += segment.DoneOffset - segment.StartOffset
	}
	return done
}

func (svc *Service) shouldSegment(task store.DownloadTask, entry backend.Entry) bool {
	if !task.StrongVersion || !entry.HasStrongVersion() || entry.Size <= 0 {
		return false
	}
	opts := svc.CurrentOptions()
	return len(planSegments(entry.Size, opts.SegmentThreads, opts.SegmentMinBytes)) >= 2
}

func (svc *Service) prepareSegments(
	ctx context.Context,
	task store.DownloadTask,
	entry backend.Entry,
	existing []store.DownloadSegment,
	stagingExists bool,
	stagingSize int64,
) ([]store.DownloadSegment, bool, error) {
	if isMultiSegmentPlan(existing, entry.Size) {
		maxDone := int64(0)
		for _, segment := range existing {
			if segment.DoneOffset > maxDone {
				maxDone = segment.DoneOffset
			}
		}
		if stagingExists && stagingSize >= maxDone && maxDone > 0 {
			return existing, false, nil
		}
		for index := range existing {
			existing[index].DoneOffset = existing[index].StartOffset
		}
		if err := svc.store.ReplaceSegments(ctx, task.ID, existing); err != nil {
			return nil, false, err
		}
		return existing, stagingExists && stagingSize > 0, nil
	}

	opts := svc.CurrentOptions()
	planned := planSegments(entry.Size, opts.SegmentThreads, opts.SegmentMinBytes)
	if len(planned) < 2 {
		return nil, false, fmt.Errorf("segment plan requires at least two ranges")
	}
	truncate := stagingExists && stagingSize > 0
	if len(existing) == 0 && stagingExists && stagingSize > 0 {
		prefix := stagingSize
		if prefix > entry.Size {
			prefix = entry.Size
		}
		for index := range planned {
			switch {
			case prefix >= planned[index].EndOffset:
				planned[index].DoneOffset = planned[index].EndOffset
			case prefix > planned[index].StartOffset:
				planned[index].DoneOffset = prefix
			}
		}
		truncate = false
	}
	if err := svc.store.ReplaceSegments(ctx, task.ID, planned); err != nil {
		return nil, false, err
	}
	return planned, truncate, nil
}

type segmentProgress struct {
	mu       sync.Mutex
	done     int64
	session  int64
	currents []int64
}

func newSegmentProgress(segments []store.DownloadSegment) *segmentProgress {
	progress := &segmentProgress{currents: make([]int64, len(segments))}
	for index, segment := range segments {
		progress.currents[index] = segment.DoneOffset
		progress.done += segment.DoneOffset - segment.StartOffset
	}
	return progress
}

func (progress *segmentProgress) add(index int, n int64, total int64, act *activeAttempt) {
	progress.mu.Lock()
	progress.currents[index] += n
	progress.done += n
	progress.session += n
	done := progress.done
	session := progress.session
	act.recordProgress(done, total, session)
	progress.mu.Unlock()
}

func (progress *segmentProgress) snapshot() (int64, []int64) {
	progress.mu.Lock()
	defer progress.mu.Unlock()
	currents := make([]int64, len(progress.currents))
	copy(currents, progress.currents)
	return progress.done, currents
}

func (progress *segmentProgress) current(index int) int64 {
	progress.mu.Lock()
	defer progress.mu.Unlock()
	return progress.currents[index]
}

func (svc *Service) downloadSegmented(
	ctx context.Context,
	fsStore *localfs.Store,
	task store.DownloadTask,
	entry backend.Entry,
	act *activeAttempt,
	existing []store.DownloadSegment,
) (int64, error) {
	stagingSize, stagingExists, err := fsStore.StagingSize(task.ID)
	if err != nil {
		return 0, err
	}
	segments, truncate, err := svc.prepareSegments(ctx, task, entry, existing, stagingExists, stagingSize)
	if err != nil {
		return 0, err
	}

	file, err := fsStore.OpenStagingForWriteAt(task.ID, truncate)
	if err != nil {
		return 0, err
	}
	defer file.Close()

	progress := newSegmentProgress(segments)
	act.setTotals(progress.done, entry.Size, 0)

	runCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	stopProgress := make(chan struct{})
	progressStopped := make(chan struct{})
	go func() {
		defer close(progressStopped)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		lastPersist := time.Now()
		for {
			select {
			case <-stopProgress:
				return
			case now := <-ticker.C:
				svc.broker.Publish(events.Event{Type: "task.updated", SourceID: task.SourceID, TaskID: task.ID})
				if now.Sub(lastPersist) >= 5*time.Second {
					_ = file.Sync()
					svc.persistSegmentProgress(task, segments, progress, entry.Size)
					lastPersist = now
				}
			}
		}
	}()

	limit := svc.configuredSegmentLimit(len(segments))
	congestionRounds := 0
	var runErr error
	for {
		pending := pendingSegmentIndices(segments, progress)
		if len(pending) == 0 {
			break
		}
		configuredLimit := svc.configuredSegmentLimit(len(segments))
		if svc.CurrentOptions().SegmentThreads <= 1 {
			runErr = errSingleStreamRequested
			break
		}
		if configuredLimit < limit {
			limit = configuredLimit
		}
		roundLimit := limit
		if roundLimit > len(pending) {
			roundLimit = len(pending)
		}
		roundCtx, roundCancel := context.WithCancel(runCtx)
		preempted := make(chan struct{}, 1)
		monitorDone := make(chan struct{})
		go func() {
			defer close(monitorDone)
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			for {
				select {
				case <-roundCtx.Done():
					return
				case <-ticker.C:
					if svc.configuredSegmentLimit(len(segments)) < roundLimit {
						select {
						case preempted <- struct{}{}:
						default:
						}
						roundCancel()
						return
					}
				}
			}
		}()
		runErr = svc.runSegmentRound(roundCtx, file, task, entry, act, segments, pending, roundLimit, progress)
		roundCancel()
		<-monitorDone
		select {
		case <-preempted:
			configuredLimit = svc.configuredSegmentLimit(len(segments))
			if configuredLimit < limit {
				limit = configuredLimit
			}
			runErr = nil
			continue
		default:
		}
		if runErr == nil {
			continue
		}
		if ctxErr := ctx.Err(); ctxErr != nil {
			runErr = ctxErr
			break
		}
		if !isCongestionError(runErr) || limit <= 1 {
			break
		}
		nextLimit := limit / 2
		if nextLimit < 1 {
			nextLimit = 1
		}
		if nextLimit >= limit {
			nextLimit = limit - 1
		}
		limit = nextLimit
		congestionRounds++
		if sleepErr := sleepWithContext(runCtx, congestionRetryDelay(runErr, congestionRounds)); sleepErr != nil {
			runErr = sleepErr
			break
		}
	}
	close(stopProgress)
	<-progressStopped

	if errors.Is(runErr, errSingleStreamRequested) {
		downgraded, err := svc.downgradeSegmentedToSingle(ctx, file, task, segments, progress, entry.Size)
		if err != nil {
			return downgraded, err
		}
		return downgraded, errSingleStreamRequested
	}

	if runErr == nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			runErr = ctxErr
		}
	}

	if runErr != nil {
		_ = file.Sync()
		svc.persistSegmentProgress(task, segments, progress, entry.Size)
		return progress.done, runErr
	}

	if err := file.Truncate(entry.Size); err != nil {
		return progress.done, err
	}
	if err := file.Sync(); err != nil {
		return progress.done, err
	}
	svc.persistSegmentProgress(task, segments, progress, entry.Size)
	return progress.done, nil
}

func (svc *Service) downgradeSegmentedToSingle(
	ctx context.Context,
	file *os.File,
	task store.DownloadTask,
	segments []store.DownloadSegment,
	progress *segmentProgress,
	total int64,
) (int64, error) {
	_, currents := progress.snapshot()
	prefix := int64(0)
	for index, segment := range segments {
		current := segment.StartOffset
		if index < len(currents) {
			current = currents[index]
		}
		if current >= segment.EndOffset {
			prefix = segment.EndOffset
			continue
		}
		prefix = current
		break
	}
	if err := file.Truncate(prefix); err != nil {
		return prefix, err
	}
	if err := file.Sync(); err != nil {
		return prefix, err
	}
	if err := svc.store.DeleteSegments(ctx, task.ID); err != nil {
		return prefix, err
	}
	_ = svc.store.UpdateAttemptProgress(context.Background(), task.ID, task.Attempt, prefix, total)
	return prefix, nil
}

func (svc *Service) configuredSegmentLimit(segmentCount int) int {
	limit := svc.CurrentOptions().SegmentThreads
	if limit > segmentCount {
		limit = segmentCount
	}
	if limit < 1 {
		limit = 1
	}
	return limit
}

func pendingSegmentIndices(segments []store.DownloadSegment, progress *segmentProgress) []int {
	pending := make([]int, 0, len(segments))
	for index, segment := range segments {
		if progress.current(index) < segment.EndOffset {
			pending = append(pending, index)
		}
	}
	return pending
}

func (svc *Service) runSegmentRound(
	parent context.Context,
	file *os.File,
	task store.DownloadTask,
	entry backend.Entry,
	act *activeAttempt,
	segments []store.DownloadSegment,
	pending []int,
	limit int,
	progress *segmentProgress,
) error {
	roundCtx, cancel := context.WithCancel(parent)
	defer cancel()

	jobs := make(chan int, len(pending))
	for _, index := range pending {
		jobs <- index
	}
	close(jobs)

	results := make(chan error, limit)
	var wg sync.WaitGroup
	for worker := 0; worker < limit; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var workerErr error
			for index := range jobs {
				if err := roundCtx.Err(); err != nil {
					workerErr = err
					break
				}
				current := progress.current(index)
				work := segments[index]
				work.DoneOffset = current
				if work.Complete() {
					continue
				}
				if err := svc.runSegment(roundCtx, file, task, entry, act, work, index, progress); err != nil {
					workerErr = err
					cancel()
					break
				}
			}
			results <- workerErr
		}()
	}
	wg.Wait()
	close(results)

	var firstErr error
	var canceledErr error
	for err := range results {
		if err == nil {
			continue
		}
		if errors.Is(err, context.Canceled) {
			if canceledErr == nil {
				canceledErr = err
			}
			continue
		}
		if firstErr == nil {
			firstErr = err
		}
	}
	if firstErr != nil {
		return firstErr
	}
	if len(pendingSegmentIndices(segments, progress)) == 0 {
		return nil
	}
	if err := parent.Err(); err != nil {
		return err
	}
	if canceledErr != nil {
		return canceledErr
	}
	return context.Canceled
}

func (svc *Service) persistSegmentProgress(
	task store.DownloadTask,
	segments []store.DownloadSegment,
	progress *segmentProgress,
	total int64,
) {
	done, currents := progress.snapshot()
	_ = svc.store.UpdateAttemptProgress(context.Background(), task.ID, task.Attempt, done, total)
	offsets := make(map[int]int64, len(currents))
	for index, current := range currents {
		if index >= len(segments) {
			continue
		}
		offsets[segments[index].Index] = current
	}
	_ = svc.store.UpdateSegments(context.Background(), task.ID, offsets)
}

func (svc *Service) runSegment(
	ctx context.Context,
	file *os.File,
	task store.DownloadTask,
	entry backend.Entry,
	act *activeAttempt,
	segment store.DownloadSegment,
	progressIndex int,
	progress *segmentProgress,
) error {
	current := segment.DoneOffset
	connectionFailures := 0
	for current < segment.EndOffset {
		if err := ctx.Err(); err != nil {
			return err
		}
		remaining := segment.EndOffset - current
		stream, err := svc.content.OpenOriginal(ctx, backend.OpenExpectation{
			ObjectID:           task.RemoteID,
			ExpectedVersionKey: task.VersionKey,
			ExpectedGCID:       task.GCID,
			ExpectedSize:       entry.Size,
			Offset:             current,
			Length:             remaining,
		})
		if err != nil {
			if backend.KindOf(err) == backend.ErrRangeNotSupported {
				return fmt.Errorf("%w: %v", errRangeUnsupported, err)
			}
			if isCongestionError(err) {
				return err
			}
			if backend.IsRetryable(err) && connectionFailures+1 < maxSegmentConnectionAttempts {
				connectionFailures++
				if sleepErr := sleepWithContext(ctx, segmentRetryDelay(err, connectionFailures)); sleepErr != nil {
					return sleepErr
				}
				continue
			}
			return err
		}
		if stream.Offset != current || stream.TotalSize != entry.Size {
			_ = stream.Reader.Close()
			return &backend.Error{Kind: backend.ErrProtocol, Message: "segment stream returned unexpected offset or total size"}
		}

		attemptStart := current
		err = svc.copySegment(ctx, file, stream.Reader, &current, segment.EndOffset, entry.Size, progressIndex, progress, act)
		_ = stream.Reader.Close()
		if err == nil {
			return nil
		}
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return err
		}
		if backend.KindOf(err) == backend.ErrRangeNotSupported {
			return fmt.Errorf("%w: %v", errRangeUnsupported, err)
		}
		if isCongestionError(err) {
			return err
		}
		if current > attemptStart {
			connectionFailures = 0
		}
		if backend.IsRetryable(err) && connectionFailures+1 < maxSegmentConnectionAttempts {
			connectionFailures++
			if sleepErr := sleepWithContext(ctx, segmentRetryDelay(err, connectionFailures)); sleepErr != nil {
				return sleepErr
			}
			continue
		}
		return err
	}
	return nil
}

func (svc *Service) copySegment(
	ctx context.Context,
	file *os.File,
	reader io.Reader,
	current *int64,
	end int64,
	total int64,
	progressIndex int,
	progress *segmentProgress,
	act *activeAttempt,
) error {
	buf := make([]byte, segmentBufferBytes)
	for *current < end {
		if err := ctx.Err(); err != nil {
			return err
		}
		remaining := end - *current
		readBuf := buf
		if remaining < int64(len(readBuf)) {
			readBuf = readBuf[:remaining]
		}
		n, readErr := readWithWatchdog(ctx, reader, readBuf, svc.opts.NoProgressTimeout)
		if n > 0 {
			if int64(n) > remaining {
				return &backend.Error{Kind: backend.ErrProtocol, Message: "segment stream returned bytes beyond requested range"}
			}
			written, writeErr := file.WriteAt(readBuf[:n], *current)
			if writeErr != nil {
				return writeErr
			}
			if written != n {
				return io.ErrShortWrite
			}
			*current += int64(written)
			progress.add(progressIndex, int64(written), total, act)
		}
		if readErr != nil {
			if errors.Is(readErr, io.EOF) {
				if *current == end {
					return nil
				}
				return &backend.Error{Kind: backend.ErrTransient, Message: fmt.Sprintf("segment ended early at %d/%d", *current, end)}
			}
			if errors.Is(readErr, context.Canceled) || errors.Is(readErr, context.DeadlineExceeded) {
				return readErr
			}
			if backend.KindOf(readErr) != "" {
				return readErr
			}
			return &backend.Error{Kind: backend.ErrTransient, Message: "segment read failed", Cause: readErr}
		}
	}
	return nil
}

func segmentRetryDelay(err error, attempt int) time.Duration {
	var backendErr *backend.Error
	if errors.As(err, &backendErr) && backendErr.RetryAfter > 0 {
		return backendErr.RetryAfter
	}
	if attempt < 1 {
		attempt = 1
	}
	delay := time.Duration(1<<(attempt-1)) * time.Second
	if delay > 8*time.Second {
		delay = 8 * time.Second
	}
	return delay
}

func isCongestionError(err error) bool {
	var backendErr *backend.Error
	if !errors.As(err, &backendErr) {
		return false
	}
	return backendErr.Kind == backend.ErrRateLimited ||
		backendErr.StatusCode == http.StatusTooManyRequests ||
		backendErr.StatusCode == http.StatusServiceUnavailable
}

func congestionRetryDelay(err error, attempt int) time.Duration {
	var backendErr *backend.Error
	if errors.As(err, &backendErr) && backendErr.RetryAfter > 0 {
		if backendErr.RetryAfter > 60*time.Second {
			return 60 * time.Second
		}
		return backendErr.RetryAfter
	}
	if attempt < 1 {
		attempt = 1
	}
	delay := time.Duration(1<<(attempt-1)) * time.Second
	if delay > 8*time.Second {
		delay = 8 * time.Second
	}
	return delay
}

func sleepWithContext(ctx context.Context, duration time.Duration) error {
	timer := time.NewTimer(duration)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
