package catalog

import (
	"context"
	"errors"
	"fmt"
	"log"
	"path"
	"strings"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/store"
)

var (
	ErrInvalidSource = errors.New("invalid source configuration")
)

const (
	defaultFailureThreshold = 3
	maxFailureThreshold     = 20
)

type CreateSourceInput struct {
	RemoteRootID     string `json:"remote_root_id"`
	LocalAlias       string `json:"local_alias"`
	InitialMode      string `json:"initial_mode"`
	ScanIntervalSec  int    `json:"scan_interval_sec"`
	MinFileSizeBytes int64  `json:"min_file_size_bytes"`
	Enabled          *bool  `json:"enabled"`
}

type Service struct {
	store            *store.Store
	remote           backend.Catalog
	broker           *events.Broker
	accountID        string
	defaultInterval  time.Duration
	failureThreshold int
	maxRetries       int

	mu       sync.Mutex
	inFlight map[string]bool
	pending  map[string]bool
	wakeCh   chan struct{}
	onQueued func()
}

func NewService(
	st *store.Store,
	remote backend.Catalog,
	broker *events.Broker,
	accountID string,
	defaultInterval time.Duration,
	maxRetries int,
) *Service {
	if defaultInterval < 10*time.Second {
		defaultInterval = 5 * time.Minute
	}
	return &Service{
		store:            st,
		remote:           remote,
		broker:           broker,
		accountID:        accountID,
		defaultInterval:  defaultInterval,
		failureThreshold: defaultFailureThreshold,
		maxRetries:       maxRetries,
		inFlight:         make(map[string]bool),
		pending:          make(map[string]bool),
		wakeCh:           make(chan struct{}, 1),
	}
}

func (svc *Service) SetOnQueued(fn func()) {
	svc.mu.Lock()
	defer svc.mu.Unlock()
	svc.onQueued = fn
}

func (svc *Service) SetAccountID(accountID string) {
	svc.mu.Lock()
	defer svc.mu.Unlock()
	svc.accountID = strings.TrimSpace(accountID)
}

func (svc *Service) DefaultInterval() time.Duration {
	svc.mu.Lock()
	defer svc.mu.Unlock()
	return svc.defaultInterval
}

func (svc *Service) SetDefaultInterval(d time.Duration) {
	if d < 10*time.Second {
		return
	}
	svc.mu.Lock()
	svc.defaultInterval = d
	svc.mu.Unlock()
}

func (svc *Service) FailureThreshold() int {
	svc.mu.Lock()
	defer svc.mu.Unlock()
	return svc.failureThreshold
}

func (svc *Service) SetFailureThreshold(threshold int) {
	if threshold < 1 || threshold > maxFailureThreshold {
		return
	}
	svc.mu.Lock()
	svc.failureThreshold = threshold
	svc.mu.Unlock()
}

func (svc *Service) BrowseRemoteFolder(ctx context.Context, parentID string, cursor string) (backend.ListPageResult, error) {
	return svc.remote.ListPage(ctx, strings.TrimSpace(parentID), strings.TrimSpace(cursor))
}

func (svc *Service) CreateSource(ctx context.Context, input CreateSourceInput) (store.Source, error) {
	alias, err := localfs.SanitizeAlias(input.LocalAlias)
	if err != nil {
		return store.Source{}, fmt.Errorf("%w: %v", ErrInvalidSource, err)
	}
	mode := strings.TrimSpace(input.InitialMode)
	if mode == "" {
		mode = store.InitialModeDownloadExisting
	}
	if mode != store.InitialModeDownloadExisting && mode != store.InitialModeMonitorNewOnly {
		return store.Source{}, fmt.Errorf("%w: unsupported initial_mode", ErrInvalidSource)
	}
	interval := input.ScanIntervalSec
	if interval == 0 {
		interval = int(svc.DefaultInterval().Seconds())
	}
	if interval < 10 {
		return store.Source{}, fmt.Errorf("%w: scan_interval_sec must be at least 10", ErrInvalidSource)
	}
	if input.MinFileSizeBytes < 0 {
		return store.Source{}, fmt.Errorf("%w: min_file_size_bytes cannot be negative", ErrInvalidSource)
	}
	enabled := true
	if input.Enabled != nil {
		enabled = *input.Enabled
	}

	rootID := strings.TrimSpace(input.RemoteRootID)
	rootPath := "/"
	if rootID != "" {
		entry, statErr := svc.remote.Stat(ctx, rootID)
		if statErr != nil {
			return store.Source{}, statErr
		}
		if !entry.IsDir {
			return store.Source{}, fmt.Errorf("%w: remote_root_id must reference a directory", ErrInvalidSource)
		}
		rootPath = "/" + entry.Name
	}

	src, err := svc.store.CreateSource(ctx, store.Source{
		AccountID:        svc.accountID,
		RemoteRootID:     rootID,
		RemoteRootPath:   rootPath,
		LocalAlias:       alias,
		InitialMode:      mode,
		Enabled:          enabled,
		ScanIntervalSec:  interval,
		MinFileSizeBytes: input.MinFileSizeBytes,
	})
	if err != nil {
		return store.Source{}, err
	}
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: src.ID})
	if enabled {
		svc.signalLoop()
	}
	return src, nil
}

func (svc *Service) UpdateSource(ctx context.Context, id string, enabled *bool, scanIntervalSec *int, minFileSizeBytes *int64) (store.Source, error) {
	if scanIntervalSec != nil && *scanIntervalSec < 10 {
		return store.Source{}, fmt.Errorf("%w: scan_interval_sec must be at least 10", ErrInvalidSource)
	}
	if minFileSizeBytes != nil && *minFileSizeBytes < 0 {
		return store.Source{}, fmt.Errorf("%w: min_file_size_bytes cannot be negative", ErrInvalidSource)
	}
	src, err := svc.store.UpdateSource(ctx, id, enabled, scanIntervalSec, minFileSizeBytes, false)
	if err != nil {
		return store.Source{}, err
	}
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: src.ID})
	if src.Enabled {
		svc.signalLoop()
	}
	return src, nil
}

func (svc *Service) DeleteSource(ctx context.Context, id string) error {
	svc.mu.Lock()
	scanning := svc.inFlight[id]
	svc.mu.Unlock()
	if scanning {
		return store.ErrConflict
	}
	if err := svc.store.DeleteSource(ctx, id); err != nil {
		return err
	}
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: id})
	return nil
}

func (svc *Service) TriggerScan(ctx context.Context, id string) (store.Source, error) {
	src, err := svc.store.UpdateSource(ctx, id, nil, nil, nil, true)
	if err != nil {
		return store.Source{}, err
	}
	svc.mu.Lock()
	if svc.inFlight[id] {
		svc.pending[id] = true
	}
	svc.mu.Unlock()
	svc.signalLoop()
	return src, nil
}

func (svc *Service) TriggerScanAll(ctx context.Context, includeDisabled bool) (int, error) {
	sources, err := svc.store.ListSources(ctx)
	if err != nil {
		return 0, err
	}
	scheduled := 0
	for _, src := range sources {
		if !src.Enabled && !includeDisabled {
			continue
		}
		if _, err := svc.store.UpdateSource(ctx, src.ID, nil, nil, nil, true); err != nil {
			return scheduled, err
		}
		svc.mu.Lock()
		if svc.inFlight[src.ID] {
			svc.pending[src.ID] = true
		}
		svc.mu.Unlock()
		scheduled++
	}
	if scheduled > 0 {
		svc.signalLoop()
	}
	return scheduled, nil
}

func (svc *Service) signalLoop() {
	select {
	case svc.wakeCh <- struct{}{}:
	default:
	}
}

func (svc *Service) RunLoop(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		svc.runDueScans(ctx)
		select {
		case <-ctx.Done():
			return
		case <-svc.wakeCh:
		case <-ticker.C:
		}
	}
}

func (svc *Service) runDueScans(ctx context.Context) {
	if !backend.IsReady(svc.remote) {
		return
	}
	due, err := svc.store.ListDueSources(ctx, time.Now().Unix())
	if err != nil {
		return
	}
	for _, src := range due {
		if ctx.Err() != nil {
			return
		}
		svc.mu.Lock()
		running := svc.inFlight[src.ID]
		svc.mu.Unlock()
		if running {
			continue
		}
		_, _ = svc.ScanSource(ctx, src.ID)
	}
}

func (svc *Service) ScanSource(ctx context.Context, sourceID string) (store.PublishResult, error) {
	svc.mu.Lock()
	if svc.inFlight[sourceID] {
		svc.pending[sourceID] = true
		svc.mu.Unlock()
		return store.PublishResult{}, nil
	}
	svc.inFlight[sourceID] = true
	svc.mu.Unlock()

	defer func() {
		svc.mu.Lock()
		delete(svc.inFlight, sourceID)
		requeue := svc.pending[sourceID]
		delete(svc.pending, sourceID)
		svc.mu.Unlock()
		if requeue && ctx.Err() == nil {
			_, _ = svc.store.UpdateSource(context.Background(), sourceID, nil, nil, nil, true)
			svc.signalLoop()
		}
	}()

	src, err := svc.store.GetSource(ctx, sourceID)
	if err != nil {
		return store.PublishResult{}, err
	}

	if src.RemoteRootID != "" {
		rootEntry, statErr := svc.remote.Stat(ctx, src.RemoteRootID)
		if statErr != nil {
			return svc.failWithoutRun(ctx, src, fmt.Sprintf("root inaccessible: %v", statErr))
		}
		if !rootEntry.IsDir {
			return svc.failWithoutRun(ctx, src, "root object is not a directory")
		}
	}

	run, latestSrc, err := svc.store.StartScanRun(ctx, sourceID)
	if err != nil {
		return store.PublishResult{}, err
	}
	svc.broker.Publish(events.Event{Type: "scan.started", SourceID: sourceID})
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: sourceID})
	log.Printf("scan started: source=%s", sourceID)

	err = svc.collectTree(ctx, run.ID, latestSrc)
	if err != nil {
		_, _ = svc.recordScanFailure(sourceID, run.ID, err, latestSrc.ConsecutiveFailures)
		return store.PublishResult{}, err
	}

	published, err := svc.store.PublishScanRun(ctx, run.ID, svc.maxRetries)
	if err != nil {
		_, _ = svc.recordScanFailure(sourceID, run.ID, err, latestSrc.ConsecutiveFailures)
		return store.PublishResult{}, err
	}

	svc.broker.Publish(events.Event{Type: "scan.finished", SourceID: sourceID, Message: "succeeded"})
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: sourceID})
	log.Printf("scan finished: source=%s entries=%d enqueued=%d missing=%d", sourceID, published.EntriesCount, len(published.EnqueuedTasks), published.MarkedMissing)
	if len(published.EnqueuedTasks) > 0 {
		svc.broker.Publish(events.Event{Type: "task.updated", SourceID: sourceID})
		svc.broker.Publish(events.Event{
			Type:     "tasks.queued",
			SourceID: sourceID,
			Count:    len(published.EnqueuedTasks),
			Message:  fmt.Sprintf("%s 新增 %d 个下载任务", published.Source.LocalAlias, len(published.EnqueuedTasks)),
		})
		svc.mu.Lock()
		cb := svc.onQueued
		svc.mu.Unlock()
		if cb != nil {
			cb()
		}
	}
	return published, nil
}

func (svc *Service) failWithoutRun(ctx context.Context, src store.Source, message string) (store.PublishResult, error) {
	run, latestSrc, err := svc.store.StartScanRun(ctx, src.ID)
	if err != nil {
		log.Printf("scan precheck failure could not start run: source=%s", src.ID)
		svc.broker.Publish(events.Event{Type: "source.updated", SourceID: src.ID})
		return store.PublishResult{}, errors.New(message)
	}
	_, _ = svc.recordScanFailure(src.ID, run.ID, errors.New(message), latestSrc.ConsecutiveFailures)
	return store.PublishResult{}, errors.New(message)
}

func (svc *Service) recordScanFailure(sourceID string, runID string, scanErr error, previousFailures int) (store.ScanFailureResult, error) {
	threshold := svc.FailureThreshold()
	nextRetry := backoffNextScan(previousFailures)
	result, err := svc.store.FailScanRun(context.Background(), runID, sourceID, scanErr.Error(), nextRetry, threshold)
	if err != nil {
		log.Printf("scan failure state update failed: source=%s", sourceID)
		svc.broker.Publish(events.Event{Type: "scan.finished", SourceID: sourceID, Message: "failed"})
		svc.broker.Publish(events.Event{Type: "source.updated", SourceID: sourceID})
		return store.ScanFailureResult{}, err
	}

	if result.Confirmed {
		svc.broker.Publish(events.Event{Type: "scan.finished", SourceID: sourceID, Message: "failed"})
		svc.broker.Publish(events.Event{
			Type:     "scan.failed",
			SourceID: sourceID,
			Count:    result.ConsecutiveFailures,
			Message:  scanErr.Error(),
		})
		log.Printf("scan failed: source=%s failures=%d threshold=%d", sourceID, result.ConsecutiveFailures, result.Threshold)
	} else {
		svc.broker.Publish(events.Event{Type: "scan.finished", SourceID: sourceID, Message: "retrying"})
		svc.broker.Publish(events.Event{
			Type:     "scan.retrying",
			SourceID: sourceID,
			Count:    result.ConsecutiveFailures,
			Message:  scanErr.Error(),
		})
		log.Printf("scan retrying: source=%s failures=%d threshold=%d", sourceID, result.ConsecutiveFailures, result.Threshold)
	}
	svc.broker.Publish(events.Event{Type: "source.updated", SourceID: sourceID})
	return result, nil
}

type dirWork struct {
	id      string
	relPath string
}

func (svc *Service) collectTree(ctx context.Context, runID string, src store.Source) error {
	queue := []dirWork{{id: src.RemoteRootID, relPath: ""}}
	visitedDirs := map[string]struct{}{src.RemoteRootID: {}}
	seenObjects := make(map[string]struct{})

	for len(queue) > 0 {
		curr := queue[0]
		queue = queue[1:]

		cursor := ""
		seenCursors := map[string]struct{}{"": {}}
		for {
			if err := ctx.Err(); err != nil {
				return err
			}
			page, err := svc.remote.ListPage(ctx, curr.id, cursor)
			if err != nil {
				return err
			}
			batch := make([]store.ScanEntry, 0, len(page.Entries))
			for _, entry := range page.Entries {
				if entry.ID == "" || entry.Name == "" {
					return errors.New("protocol error: entry missing id or name")
				}
				if _, dup := seenObjects[entry.ID]; dup {
					return fmt.Errorf("duplicate remote object id %s in scan", entry.ID)
				}
				seenObjects[entry.ID] = struct{}{}

				rel := entry.Name
				if curr.relPath != "" {
					rel = path.Join(curr.relPath, entry.Name)
				}
				if entry.IsDir {
					if _, seen := visitedDirs[entry.ID]; seen {
						return fmt.Errorf("directory cycle detected at %s", entry.ID)
					}
					visitedDirs[entry.ID] = struct{}{}
					queue = append(queue, dirWork{id: entry.ID, relPath: rel})
				}
				batch = append(batch, store.ScanEntry{
					ScanRunID:        runID,
					SourceID:         src.ID,
					RemoteID:         entry.ID,
					ParentRemoteID:   curr.id,
					RelativePath:     rel,
					Name:             entry.Name,
					IsDir:            entry.IsDir,
					Size:             entry.Size,
					GCID:             entry.GCID,
					VersionKey:       entry.VersionKey(),
					StrongVersion:    entry.HasStrongVersion(),
					RemoteModifiedAt: entry.ModTime.Unix(),
				})
			}
			if err := svc.store.InsertScanEntries(ctx, batch); err != nil {
				return err
			}
			if page.NextCursor == "" {
				break
			}
			if _, dupCursor := seenCursors[page.NextCursor]; dupCursor {
				return fmt.Errorf("pagination cursor loop detected")
			}
			seenCursors[page.NextCursor] = struct{}{}
			cursor = page.NextCursor
		}
	}
	return nil
}

func backoffNextScan(consecutiveFailures int) int64 {
	shift := consecutiveFailures
	if shift > 7 {
		shift = 7
	}
	delay := 30 * time.Second * time.Duration(1<<shift)
	if delay > time.Hour {
		delay = time.Hour
	}
	return time.Now().Add(delay).Unix()
}
