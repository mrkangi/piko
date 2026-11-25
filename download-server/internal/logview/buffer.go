package logview

import (
	"bytes"
	"strings"
	"sync"
	"time"
)

const (
	DefaultMaxEntries = 4000
	DefaultMaxBytes   = 2 * 1024 * 1024
	maxLineBytes      = 8 * 1024
	entryOverhead     = 48
)

// Entry is one captured log line. Time is Unix milliseconds.
type Entry struct {
	Seq  int64  `json:"seq"`
	Time int64  `json:"time"`
	Text string `json:"text"`
}

// Stats describes the bounded buffer without exposing its internal storage.
type Stats struct {
	Buffered   int   `json:"buffered"`
	Bytes      int64 `json:"bytes"`
	MaxEntries int   `json:"max_entries"`
	MaxBytes   int64 `json:"max_bytes"`
	Dropped    int64 `json:"dropped"`
}

// Page is a window into the buffer.
type Page struct {
	Entries       []Entry `json:"entries"`
	FirstSeq      int64   `json:"first_seq"`
	LastSeq       int64   `json:"last_seq"`
	HasMoreBefore bool    `json:"has_more_before"`
	HasMoreAfter  bool    `json:"has_more_after"`
	Gap           bool    `json:"gap"`
	Stats
}

// Buffer is a bounded, in-memory log ring. It intentionally never grows without
// limit: the web log viewer must stay cheap even when the service runs for days.
type Buffer struct {
	mu         sync.Mutex
	entries    []Entry
	bytes      int64
	seq        int64
	dropped    int64
	partial    []byte
	maxEntries int
	maxBytes   int64
}

func New(maxEntries int, maxBytes int64) *Buffer {
	if maxEntries <= 0 {
		maxEntries = DefaultMaxEntries
	}
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	return &Buffer{maxEntries: maxEntries, maxBytes: maxBytes}
}

// Write implements io.Writer so the buffer can be attached to the standard
// logger. It always reports success even if a line had to be truncated.
func (b *Buffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	b.partial = append(b.partial, p...)
	for {
		idx := bytes.IndexByte(b.partial, '\n')
		if idx < 0 {
			break
		}
		b.appendLineLocked(string(b.partial[:idx]))
		b.partial = b.partial[idx+1:]
	}
	if len(b.partial) > maxLineBytes {
		b.appendLineLocked(string(b.partial[:maxLineBytes]) + " …(truncated)")
		b.partial = b.partial[:0]
	}
	if cap(b.partial) > 4*maxLineBytes {
		compact := make([]byte, len(b.partial))
		copy(compact, b.partial)
		b.partial = compact
	}
	return len(p), nil
}

func (b *Buffer) appendLineLocked(raw string) {
	text := strings.TrimRight(raw, "\r")
	if len(text) > maxLineBytes {
		text = text[:maxLineBytes] + " …(truncated)"
	}
	b.seq++
	entry := Entry{Seq: b.seq, Time: time.Now().UnixMilli(), Text: text}
	b.entries = append(b.entries, entry)
	b.bytes += int64(len(text)) + entryOverhead
	for len(b.entries) > b.maxEntries || (b.bytes > b.maxBytes && len(b.entries) > 1) {
		b.dropped++
		b.bytes -= int64(len(b.entries[0].Text)) + entryOverhead
		b.entries[0] = Entry{}
		b.entries = b.entries[1:]
	}
}

// Tail returns up to limit of the newest entries in chronological order.
func (b *Buffer) Tail(limit int) Page {
	b.mu.Lock()
	defer b.mu.Unlock()
	if limit <= 0 || limit > len(b.entries) {
		limit = len(b.entries)
	}
	start := len(b.entries) - limit
	entries := b.copyLocked(start, len(b.entries))
	return Page{
		Entries:       entries,
		FirstSeq:      firstSeqLocked(entries, b.seq),
		LastSeq:       b.seq,
		HasMoreBefore: start > 0,
		Stats:         b.statsLocked(),
	}
}

// After returns entries with Seq greater than after. When the requested cursor
// has already fallen out of the ring, Gap is set so the caller can reload.
func (b *Buffer) After(after int64, limit int) Page {
	b.mu.Lock()
	defer b.mu.Unlock()

	if limit <= 0 || limit > b.maxEntries {
		limit = b.maxEntries
	}
	start := 0
	for start < len(b.entries) && b.entries[start].Seq <= after {
		start++
	}
	gap := after > 0 && start == 0 && len(b.entries) > 0 && b.entries[0].Seq > after+1
	end := start + limit
	if end > len(b.entries) {
		end = len(b.entries)
	}
	entries := b.copyLocked(start, end)
	return Page{
		Entries:       entries,
		FirstSeq:      firstSeqLocked(entries, b.seq),
		LastSeq:       b.seq,
		HasMoreBefore: start > 0,
		HasMoreAfter:  end < len(b.entries),
		Gap:           gap,
		Stats:         b.statsLocked(),
	}
}

// Before returns the newest entries older than before, in chronological order.
func (b *Buffer) Before(before int64, limit int) Page {
	b.mu.Lock()
	defer b.mu.Unlock()

	if limit <= 0 || limit > b.maxEntries {
		limit = b.maxEntries
	}
	end := 0
	for end < len(b.entries) && b.entries[end].Seq < before {
		end++
	}
	start := end - limit
	if start < 0 {
		start = 0
	}
	entries := b.copyLocked(start, end)
	return Page{
		Entries:       entries,
		FirstSeq:      firstSeqLocked(entries, b.seq),
		LastSeq:       b.seq,
		HasMoreBefore: start > 0,
		HasMoreAfter:  end < len(b.entries),
		Stats:         b.statsLocked(),
	}
}

func (b *Buffer) copyLocked(start, end int) []Entry {
	if start >= end {
		return []Entry{}
	}
	out := make([]Entry, end-start)
	copy(out, b.entries[start:end])
	return out
}

func (b *Buffer) statsLocked() Stats {
	return Stats{
		Buffered:   len(b.entries),
		Bytes:      b.bytes,
		MaxEntries: b.maxEntries,
		MaxBytes:   b.maxBytes,
		Dropped:    b.dropped,
	}
}

func firstSeqLocked(entries []Entry, fallback int64) int64 {
	if len(entries) == 0 {
		return fallback
	}
	return entries[0].Seq
}
