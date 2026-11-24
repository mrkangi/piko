package localfs

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestProbeAndNonOverwritingCommit(t *testing.T) {
	rootDir := t.TempDir()
	store, err := Open(rootDir)
	if err != nil {
		t.Fatalf("Open failed: %v", err)
	}
	defer store.Close()

	if err := store.Probe(); err != nil {
		t.Fatalf("Probe failed: %v", err)
	}

	file, offset, err := store.OpenStagingForAppend("task-1", false)
	if err != nil {
		t.Fatalf("OpenStagingForAppend failed: %v", err)
	}
	if offset != 0 {
		t.Fatalf("expected offset 0, got %d", offset)
	}
	if _, err := file.WriteString("hello world"); err != nil {
		t.Fatal(err)
	}
	_ = file.Close()

	size, digest, err := store.ComputeStagingDigest("task-1")
	if err != nil {
		t.Fatalf("ComputeStagingDigest failed: %v", err)
	}
	if size != 11 {
		t.Fatalf("unexpected staging size %d", size)
	}

	if err := store.CommitStaging("task-1", "Movies/2024/clip.mp4"); err != nil {
		t.Fatalf("CommitStaging failed: %v", err)
	}

	verified, err := store.VerifyCommittedFile("task-1", "Movies/2024/clip.mp4", 11, digest)
	if err != nil || !verified {
		t.Fatalf("VerifyCommittedFile failed: verified=%v err=%v", verified, err)
	}

	if err := store.RemoveStaging("task-1"); err != nil {
		t.Fatalf("RemoveStaging failed: %v", err)
	}

	verifiedAfterRemove, err := store.VerifyCommittedFile("task-1", "Movies/2024/clip.mp4", 11, digest)
	if err != nil || !verifiedAfterRemove {
		t.Fatalf("expected digest verification to succeed after staging cleanup: verified=%v err=%v", verifiedAfterRemove, err)
	}

	file2, _, err := store.OpenStagingForAppend("task-2", false)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = file2.WriteString("other")
	_ = file2.Close()
	if err := store.CommitStaging("task-2", "Movies/2024/clip.mp4"); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict on existing target, got %v", err)
	}
}

func TestCommitDoesNotOverwriteExistingDestination(t *testing.T) {
	store, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("Open failed: %v", err)
	}
	defer store.Close()

	if err := store.Probe(); err != nil {
		t.Fatalf("Probe failed: %v", err)
	}

	target := filepath.Join(store.RootPath(), "Movies/2024/clip.mp4")
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(target, []byte("user-owned"), 0o644); err != nil {
		t.Fatal(err)
	}

	file, _, err := store.OpenStagingForAppend("task-1", false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := file.WriteString("downloaded-bytes"); err != nil {
		t.Fatal(err)
	}
	_ = file.Close()

	if err := store.CommitStaging("task-1", "Movies/2024/clip.mp4"); !errors.Is(err, ErrConflict) {
		t.Fatalf("expected ErrConflict on existing target, got %v", err)
	}
	raw, err := os.ReadFile(target)
	if err != nil || string(raw) != "user-owned" {
		t.Fatalf("existing target modified: %q err=%v", string(raw), err)
	}
}

func TestRejectSymlinkEscapeAndTraversal(t *testing.T) {
	rootDir := t.TempDir()
	outsideDir := t.TempDir()
	if err := os.Symlink(outsideDir, filepath.Join(rootDir, "escape")); err != nil {
		t.Fatal(err)
	}

	store, err := Open(rootDir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()

	if _, _, err := store.DestinationExists("escape/file.mkv"); !errors.Is(err, ErrSymlinkBlocked) {
		t.Fatalf("expected ErrSymlinkBlocked, got %v", err)
	}
	if _, _, err := store.DestinationExists("../outside.mkv"); !errors.Is(err, ErrInvalidPath) {
		t.Fatalf("expected ErrInvalidPath, got %v", err)
	}
	if _, err := MapRelativePath("Shows", "../secret.mkv", "obj1", nil); err == nil {
		t.Fatalf("expected traversal rejection")
	}
}

func TestMapRelativePathSanitizesAndResolvesCollisions(t *testing.T) {
	seen := map[string]string{}
	first, err := MapRelativePath("Shows", "Season 1/CON.mkv", "obj-1", seen)
	if err != nil {
		t.Fatal(err)
	}
	seen[NormalizedCollisionKey(first)] = "obj-1"
	if first != "Shows/Season 1/_CON.mkv" {
		t.Fatalf("unexpected sanitized path: %s", first)
	}

	second, err := MapRelativePath("Shows", "season 1/con.mkv", "obj-2", seen)
	if err != nil {
		t.Fatal(err)
	}
	if second == first {
		t.Fatalf("expected case collision disambiguation, got %s", second)
	}
}
