package logview

import (
	"fmt"
	"testing"
)

func TestBufferTailAfterBefore(t *testing.T) {
	b := New(10, 1<<20)
	for i := 1; i <= 5; i++ {
		_, _ = fmt.Fprintf(b, "line %d\n", i)
	}

	tail := b.Tail(2)
	if len(tail.Entries) != 2 || tail.Entries[0].Text != "line 4" || tail.Entries[1].Text != "line 5" {
		t.Fatalf("unexpected tail: %#v", tail.Entries)
	}
	if !tail.HasMoreBefore {
		t.Fatal("expected more entries before tail")
	}

	after := b.After(3, 10)
	if len(after.Entries) != 2 || after.Entries[0].Text != "line 4" || after.Entries[1].Text != "line 5" {
		t.Fatalf("unexpected after page: %#v", after.Entries)
	}

	before := b.Before(4, 2)
	if len(before.Entries) != 2 || before.Entries[0].Text != "line 2" || before.Entries[1].Text != "line 3" {
		t.Fatalf("unexpected before page: %#v", before.Entries)
	}
}

func TestBufferDropsOldestAndReportsGap(t *testing.T) {
	b := New(2, 1<<20)
	for i := 1; i <= 5; i++ {
		_, _ = fmt.Fprintf(b, "line %d\n", i)
	}

	page := b.Tail(2)
	if page.Buffered != 2 || page.Dropped != 3 {
		t.Fatalf("unexpected stats: %#v", page.Stats)
	}
	if page.Entries[0].Text != "line 4" || page.Entries[1].Text != "line 5" {
		t.Fatalf("unexpected retained entries: %#v", page.Entries)
	}

	gap := b.After(1, 10)
	if !gap.Gap {
		t.Fatal("expected gap when cursor predates the retained window")
	}
}

func TestBufferTruncatesLongLinesAndJoinsPartialWrites(t *testing.T) {
	b := New(10, 1<<20)
	_, _ = b.Write([]byte("first "))
	_, _ = b.Write([]byte("second\n"))
	long := make([]byte, maxLineBytes+100)
	for i := range long {
		long[i] = 'x'
	}
	_, _ = b.Write(append(long, '\n'))

	page := b.Tail(2)
	if len(page.Entries) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(page.Entries))
	}
	if page.Entries[0].Text != "first second" {
		t.Fatalf("partial writes were not joined: %q", page.Entries[0].Text)
	}
	if len(page.Entries[1].Text) > maxLineBytes+len(" …(truncated)") {
		t.Fatalf("long line was not truncated: %d", len(page.Entries[1].Text))
	}
}
