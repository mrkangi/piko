package backend

import (
	"bytes"
	"context"
	"io"
	"slices"
	"strings"
	"sync"
	"time"
)

type FakeNode struct {
	Entry   Entry
	Payload []byte
}

type FakeBackend struct {
	mu           sync.Mutex
	nodes        map[string]FakeNode
	children     map[string][]string
	pageSize     int
	listHook     func(parentID string, cursor string, attempt int) error
	statHook     func(objectID string, attempt int) error
	openHook     func(expectation OpenExpectation, attempt int) (*OpenedStream, error)
	listAttempts map[string]int
	statAttempts map[string]int
	openAttempts map[string]int
}

func NewFakeBackend() *FakeBackend {
	now := time.Unix(1700000000, 0).UTC()
	return &FakeBackend{
		nodes: map[string]FakeNode{
			"": {Entry: Entry{ID: "", Name: "/", IsDir: true, ModTime: now}},
		},
		children:     make(map[string][]string),
		pageSize:     50,
		listAttempts: make(map[string]int),
		statAttempts: make(map[string]int),
		openAttempts: make(map[string]int),
	}
}

func (fake *FakeBackend) SetPageSize(size int) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	if size > 0 {
		fake.pageSize = size
	}
}

func (fake *FakeBackend) SetListHook(fn func(parentID string, cursor string, attempt int) error) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	fake.listHook = fn
}

func (fake *FakeBackend) SetStatHook(fn func(objectID string, attempt int) error) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	fake.statHook = fn
}

func (fake *FakeBackend) SetOpenHook(fn func(expectation OpenExpectation, attempt int) (*OpenedStream, error)) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	fake.openHook = fn
}

func (fake *FakeBackend) PutDir(id, parentID, name string) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	entry := Entry{
		ID:       id,
		ParentID: parentID,
		Name:     name,
		IsDir:    true,
		ModTime:  time.Now().UTC(),
	}
	fake.putNodeLocked(entry, nil)
}

func (fake *FakeBackend) PutFile(id, parentID, name, gcid string, payload []byte, modTime time.Time) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	copied := slices.Clone(payload)
	entry := Entry{
		ID:       id,
		ParentID: parentID,
		Name:     name,
		IsDir:    false,
		Size:     int64(len(copied)),
		ModTime:  modTime.UTC(),
		GCID:     gcid,
		Verified: strings.TrimSpace(gcid) != "",
	}
	fake.putNodeLocked(entry, copied)
}

func (fake *FakeBackend) Remove(id string) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	node, ok := fake.nodes[id]
	if !ok {
		return
	}
	delete(fake.nodes, id)
	list := fake.children[node.Entry.ParentID]
	filtered := list[:0]
	for _, childID := range list {
		if childID != id {
			filtered = append(filtered, childID)
		}
	}
	fake.children[node.Entry.ParentID] = filtered
}

func (fake *FakeBackend) Move(id, newParentID, newName string) {
	fake.mu.Lock()
	defer fake.mu.Unlock()
	node, ok := fake.nodes[id]
	if !ok {
		return
	}
	oldParent := node.Entry.ParentID
	if oldParent != newParentID {
		oldList := fake.children[oldParent]
		filtered := oldList[:0]
		for _, childID := range oldList {
			if childID != id {
				filtered = append(filtered, childID)
			}
		}
		fake.children[oldParent] = filtered
		fake.children[newParentID] = append(fake.children[newParentID], id)
		node.Entry.ParentID = newParentID
	}
	if newName != "" {
		node.Entry.Name = newName
	}
	fake.nodes[id] = node
}

func (fake *FakeBackend) putNodeLocked(entry Entry, payload []byte) {
	if existing, ok := fake.nodes[entry.ID]; ok && existing.Entry.ParentID != entry.ParentID {
		oldList := fake.children[existing.Entry.ParentID]
		filtered := oldList[:0]
		for _, childID := range oldList {
			if childID != entry.ID {
				filtered = append(filtered, childID)
			}
		}
		fake.children[existing.Entry.ParentID] = filtered
	}
	if !slices.Contains(fake.children[entry.ParentID], entry.ID) {
		fake.children[entry.ParentID] = append(fake.children[entry.ParentID], entry.ID)
	}
	fake.nodes[entry.ID] = FakeNode{Entry: entry, Payload: payload}
}

func (fake *FakeBackend) ListPage(ctx context.Context, parentID string, cursor string) (ListPageResult, error) {
	if err := ctx.Err(); err != nil {
		return ListPageResult{}, err
	}
	fake.mu.Lock()
	key := parentID + "|" + cursor
	fake.listAttempts[key]++
	attempt := fake.listAttempts[key]
	hook := fake.listHook
	_, exists := fake.nodes[parentID]
	childIDs := slices.Clone(fake.children[parentID])
	pageSize := fake.pageSize
	fake.mu.Unlock()

	if hook != nil {
		if err := hook(parentID, cursor, attempt); err != nil {
			return ListPageResult{}, err
		}
	}
	if !exists {
		return ListPageResult{}, &Error{Kind: ErrNotFound, StatusCode: 404, Message: "remote folder not found"}
	}

	start := 0
	if cursor != "" {
		for idx, id := range childIDs {
			if id == cursor {
				start = idx
				break
			}
		}
	}
	end := start + pageSize
	if end > len(childIDs) {
		end = len(childIDs)
	}
	nextCursor := ""
	if end < len(childIDs) {
		nextCursor = childIDs[end]
	}

	fake.mu.Lock()
	entries := make([]Entry, 0, end-start)
	for _, childID := range childIDs[start:end] {
		if node, ok := fake.nodes[childID]; ok {
			entries = append(entries, node.Entry)
		}
	}
	fake.mu.Unlock()

	return ListPageResult{Entries: entries, NextCursor: nextCursor}, nil
}

func (fake *FakeBackend) Stat(ctx context.Context, objectID string) (Entry, error) {
	if err := ctx.Err(); err != nil {
		return Entry{}, err
	}
	fake.mu.Lock()
	fake.statAttempts[objectID]++
	attempt := fake.statAttempts[objectID]
	hook := fake.statHook
	node, ok := fake.nodes[objectID]
	fake.mu.Unlock()

	if hook != nil {
		if err := hook(objectID, attempt); err != nil {
			return Entry{}, err
		}
	}
	if !ok {
		return Entry{}, &Error{Kind: ErrNotFound, StatusCode: 404, Message: "remote object not found"}
	}
	return node.Entry, nil
}

func (fake *FakeBackend) OpenOriginal(ctx context.Context, expectation OpenExpectation) (*OpenedStream, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	fake.mu.Lock()
	fake.openAttempts[expectation.ObjectID]++
	attempt := fake.openAttempts[expectation.ObjectID]
	hook := fake.openHook
	node, ok := fake.nodes[expectation.ObjectID]
	fake.mu.Unlock()

	if hook != nil {
		stream, err := hook(expectation, attempt)
		if stream != nil || err != nil {
			return stream, err
		}
	}
	if !ok || node.Entry.IsDir {
		return nil, &Error{Kind: ErrNotFound, StatusCode: 404, Message: "remote file not found"}
	}
	if expectation.ExpectedVersionKey != "" && node.Entry.VersionKey() != expectation.ExpectedVersionKey {
		return nil, &Error{Kind: ErrVersionMismatch, Message: "remote object version changed"}
	}
	total := int64(len(node.Payload))
	if expectation.Offset < 0 || expectation.Offset > total {
		return nil, &Error{Kind: ErrRangeInvalid, StatusCode: 416, Message: "offset out of range"}
	}
	slice := slices.Clone(node.Payload[expectation.Offset:])
	return &OpenedStream{
		Reader:          io.NopCloser(bytes.NewReader(slice)),
		Offset:          expectation.Offset,
		TotalSize:       total,
		VerifiedVersion: node.Entry.VersionKey(),
		Verifiable:      node.Entry.HasStrongVersion(),
	}, nil
}
