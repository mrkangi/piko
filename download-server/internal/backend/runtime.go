package backend

import (
	"context"
	"sync"
)

var ErrNotConfigured = &Error{Kind: ErrAuth, Message: "PikPak account is not configured"}

type Runtime struct {
	mu      sync.RWMutex
	catalog Catalog
	content ContentSource
}

func NewRuntime() *Runtime {
	return &Runtime{}
}

func (runtime *Runtime) Configure(catalog Catalog, content ContentSource) {
	runtime.mu.Lock()
	defer runtime.mu.Unlock()
	runtime.catalog = catalog
	runtime.content = content
}

func (runtime *Runtime) Ready() bool {
	runtime.mu.RLock()
	defer runtime.mu.RUnlock()
	return runtime.catalog != nil && runtime.content != nil
}

func (runtime *Runtime) ListPage(ctx context.Context, parentID string, cursor string) (ListPageResult, error) {
	runtime.mu.RLock()
	catalog := runtime.catalog
	runtime.mu.RUnlock()
	if catalog == nil {
		return ListPageResult{}, ErrNotConfigured
	}
	return catalog.ListPage(ctx, parentID, cursor)
}

func (runtime *Runtime) Stat(ctx context.Context, objectID string) (Entry, error) {
	runtime.mu.RLock()
	catalog := runtime.catalog
	runtime.mu.RUnlock()
	if catalog == nil {
		return Entry{}, ErrNotConfigured
	}
	return catalog.Stat(ctx, objectID)
}

func (runtime *Runtime) OpenOriginal(ctx context.Context, expectation OpenExpectation) (*OpenedStream, error) {
	runtime.mu.RLock()
	content := runtime.content
	runtime.mu.RUnlock()
	if content == nil {
		return nil, ErrNotConfigured
	}
	return content.OpenOriginal(ctx, expectation)
}

func IsReady(value any) bool {
	readiness, ok := value.(interface{ Ready() bool })
	if !ok {
		return true
	}
	return readiness.Ready()
}
