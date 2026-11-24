package localfs

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"
)

var (
	ErrConflict       = errors.New("destination path already exists")
	ErrSymlinkBlocked = errors.New("symlink encountered under download root")
	ErrInvalidPath    = errors.New("invalid relative path")
)

type Store struct {
	rootPath string
	root     *os.Root
}

func Open(downloadDir string) (*Store, error) {
	absPath, err := filepath.Abs(downloadDir)
	if err != nil {
		return nil, fmt.Errorf("resolve download root: %w", err)
	}
	if err := os.MkdirAll(absPath, 0o755); err != nil {
		return nil, fmt.Errorf("create download root: %w", err)
	}
	root, err := os.OpenRoot(absPath)
	if err != nil {
		return nil, fmt.Errorf("open download root: %w", err)
	}
	store := &Store{rootPath: absPath, root: root}
	if err := store.ensureStagingDir(); err != nil {
		_ = root.Close()
		return nil, err
	}
	return store, nil
}

func (store *Store) Close() error {
	if store == nil || store.root == nil {
		return nil
	}
	return store.root.Close()
}

func (store *Store) RootPath() string {
	return store.rootPath
}

func (store *Store) ensureStagingDir() error {
	info, err := store.root.Lstat(StagingDirName)
	if err == nil {
		if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
			return fmt.Errorf("%s must be a real directory", StagingDirName)
		}
		return nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return store.root.Mkdir(StagingDirName, 0o755)
}

func (store *Store) Probe() error {
	if err := store.ensureStagingDir(); err != nil {
		return fmt.Errorf("probe staging dir: %w", err)
	}
	probeDir := path.Join(StagingDirName, ".probe")
	_ = store.root.RemoveAll(probeDir)
	if err := store.root.MkdirAll(probeDir, 0o755); err != nil {
		return fmt.Errorf("probe mkdir: %w", err)
	}
	defer store.root.RemoveAll(probeDir)

	srcRel := path.Join(probeDir, "src.tmp")
	dstRel := path.Join(probeDir, "dst.tmp")
	if err := store.writeProbeFile(srcRel); err != nil {
		return err
	}

	if err := store.root.Rename(srcRel, dstRel); err != nil {
		return fmt.Errorf("probe rename delivery: %w", err)
	}
	if _, err := store.root.Lstat(dstRel); err != nil {
		return fmt.Errorf("probe renamed target: %w", err)
	}

	if err := ignoreDirSyncUnsupported(store.syncDir(probeDir)); err != nil {
		return fmt.Errorf("probe directory sync: %w", err)
	}
	return nil
}

func (store *Store) writeProbeFile(rel string) error {
	file, err := store.root.OpenFile(rel, os.O_CREATE|os.O_RDWR|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("probe create file: %w", err)
	}
	if _, err := file.WriteString("probe"); err != nil {
		_ = file.Close()
		return fmt.Errorf("probe write: %w", err)
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		return fmt.Errorf("probe fsync: %w", err)
	}
	return file.Close()
}

func StagingRelPath(taskID string) (string, error) {
	cleaned := strings.TrimSpace(taskID)
	if cleaned == "" || strings.ContainsAny(cleaned, "/\\\x00") || cleaned == "." || cleaned == ".." {
		return "", ErrInvalidPath
	}
	return path.Join(StagingDirName, cleaned, "payload.part"), nil
}

func (store *Store) StagingSize(taskID string) (int64, bool, error) {
	rel, err := StagingRelPath(taskID)
	if err != nil {
		return 0, false, err
	}
	if err := store.rejectSymlinks(rel); err != nil {
		return 0, false, err
	}
	info, err := store.root.Lstat(rel)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return 0, false, nil
		}
		return 0, false, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return 0, false, ErrSymlinkBlocked
	}
	return info.Size(), true, nil
}

func (store *Store) OpenStagingForAppend(taskID string, truncate bool) (*os.File, int64, error) {
	rel, err := StagingRelPath(taskID)
	if err != nil {
		return nil, 0, err
	}
	dirRel := path.Dir(rel)
	if err := store.mkdirAllConfined(dirRel); err != nil {
		return nil, 0, err
	}
	if err := store.rejectSymlinks(rel); err != nil {
		return nil, 0, err
	}
	flags := os.O_CREATE | os.O_RDWR
	if truncate {
		flags |= os.O_TRUNC
	}
	file, err := store.root.OpenFile(rel, flags, 0o644)
	if err != nil {
		return nil, 0, err
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, 0, err
	}
	if _, err := file.Seek(info.Size(), io.SeekStart); err != nil {
		_ = file.Close()
		return nil, 0, err
	}
	return file, info.Size(), nil
}

func (store *Store) RemoveStaging(taskID string) error {
	cleaned := strings.TrimSpace(taskID)
	if cleaned == "" || strings.ContainsAny(cleaned, "/\\\x00") || cleaned == "." || cleaned == ".." {
		return ErrInvalidPath
	}
	dirRel := path.Join(StagingDirName, cleaned)
	if err := store.rejectSymlinks(dirRel); err != nil {
		return err
	}
	return store.root.RemoveAll(dirRel)
}

func (store *Store) DestinationExists(relTargetPath string) (bool, int64, error) {
	exists, _, size, err := store.DestinationInfo(relTargetPath)
	return exists, size, err
}

// DestinationInfo 返回目标是否存在、是否为普通文件以及大小。目录存在时
// exists 为真但 isRegular 为假，调用方不能把目录当作可采用的本地文件。
func (store *Store) DestinationInfo(relTargetPath string) (bool, bool, int64, error) {
	cleanRel, err := validateRelativeTarget(relTargetPath)
	if err != nil {
		return false, false, 0, err
	}
	if err := store.rejectSymlinks(cleanRel); err != nil {
		return false, false, 0, err
	}
	info, err := store.root.Lstat(cleanRel)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, false, 0, nil
		}
		return false, false, 0, err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return true, false, 0, ErrSymlinkBlocked
	}
	return true, info.Mode().IsRegular(), info.Size(), nil
}

func (store *Store) ComputeStagingDigest(taskID string) (int64, string, error) {
	rel, err := StagingRelPath(taskID)
	if err != nil {
		return 0, "", err
	}
	if err := store.rejectSymlinks(rel); err != nil {
		return 0, "", err
	}
	file, err := store.root.Open(rel)
	if err != nil {
		return 0, "", err
	}
	defer file.Close()
	return hashReader(file)
}

func (store *Store) CommitStaging(taskID string, relTargetPath string) error {
	cleanRel, err := validateRelativeTarget(relTargetPath)
	if err != nil {
		return err
	}
	stagingRel, err := StagingRelPath(taskID)
	if err != nil {
		return err
	}
	if err := store.rejectSymlinks(stagingRel); err != nil {
		return err
	}
	parentRel := path.Dir(cleanRel)
	if err := store.mkdirAllConfined(parentRel); err != nil {
		return err
	}
	if err := store.rejectSymlinks(cleanRel); err != nil {
		return err
	}
	if file, err := store.root.OpenFile(stagingRel, os.O_RDWR, 0o644); err == nil {
		syncErr := file.Sync()
		_ = file.Close()
		if syncErr != nil {
			return syncErr
		}
	} else {
		return err
	}

	if _, err := store.root.Lstat(cleanRel); err == nil {
		return ErrConflict
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := store.root.Rename(stagingRel, cleanRel); err != nil {
		if errors.Is(err, os.ErrExist) {
			return ErrConflict
		}
		return err
	}
	return ignoreDirSyncUnsupported(store.syncDir(parentRel))
}

func (store *Store) VerifyCommittedFile(taskID string, relTargetPath string, expectedSize int64, expectedSHA256 string) (bool, error) {
	cleanRel, err := validateRelativeTarget(relTargetPath)
	if err != nil {
		return false, err
	}
	if err := store.rejectSymlinks(cleanRel); err != nil {
		return false, err
	}
	targetInfo, err := store.root.Lstat(cleanRel)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	if targetInfo.Mode()&os.ModeSymlink != 0 || !targetInfo.Mode().IsRegular() {
		return false, ErrSymlinkBlocked
	}
	if targetInfo.Size() != expectedSize {
		return false, nil
	}

	if stagingRel, err := StagingRelPath(taskID); err == nil {
		if stagingInfo, statErr := store.root.Lstat(stagingRel); statErr == nil && os.SameFile(targetInfo, stagingInfo) {
			return true, nil
		}
	}

	if expectedSHA256 != "" {
		file, err := store.root.Open(cleanRel)
		if err != nil {
			return false, err
		}
		defer file.Close()
		size, digest, err := hashReader(file)
		if err != nil {
			return false, err
		}
		return size == expectedSize && digest == expectedSHA256, nil
	}
	return false, nil
}

func (store *Store) AbsolutePath(relPath string) (string, error) {
	cleanRel, err := validateRelativeTarget(relPath)
	if err != nil {
		return "", err
	}
	return filepath.Join(store.rootPath, filepath.FromSlash(cleanRel)), nil
}

type DirEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

func ListLocalSubdirs(dirPath string) (string, string, []DirEntry, error) {
	target := strings.TrimSpace(dirPath)
	if target == "" {
		target = "/"
	}
	absPath, err := filepath.Abs(target)
	if err != nil {
		return "", "", nil, err
	}
	info, err := os.Stat(absPath)
	if err != nil {
		return "", "", nil, err
	}
	if !info.IsDir() {
		return "", "", nil, fmt.Errorf("not a directory: %s", absPath)
	}
	entries, err := os.ReadDir(absPath)
	if err != nil {
		return "", "", nil, err
	}
	dirs := make([]DirEntry, 0)
	for _, entry := range entries {
		name := entry.Name()
		if strings.HasPrefix(name, ".") {
			continue
		}
		if !entry.IsDir() {
			if entry.Type()&os.ModeSymlink != 0 {
				subInfo, statErr := os.Stat(filepath.Join(absPath, name))
				if statErr != nil || !subInfo.IsDir() {
					continue
				}
			} else {
				continue
			}
		}
		dirs = append(dirs, DirEntry{
			Name: name,
			Path: filepath.Join(absPath, name),
		})
	}
	parent := filepath.Dir(absPath)
	if parent == absPath {
		parent = ""
	}
	return absPath, parent, dirs, nil
}

func (store *Store) mkdirAllConfined(dirRel string) error {
	if dirRel == "." || dirRel == "" {
		return nil
	}
	parts := strings.Split(dirRel, "/")
	curr := ""
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		if part == ".." {
			return ErrInvalidPath
		}
		if curr == "" {
			curr = part
		} else {
			curr = path.Join(curr, part)
		}
		info, err := store.root.Lstat(curr)
		if err == nil {
			if info.Mode()&os.ModeSymlink != 0 {
				return ErrSymlinkBlocked
			}
			if !info.IsDir() {
				return ErrConflict
			}
			continue
		}
		if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		if err := store.root.Mkdir(curr, 0o755); err != nil {
			if !errors.Is(err, os.ErrExist) {
				return err
			}
		}
	}
	return nil
}

func (store *Store) rejectSymlinks(rel string) error {
	parts := strings.Split(rel, "/")
	curr := ""
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		if part == ".." {
			return ErrInvalidPath
		}
		if curr == "" {
			curr = part
		} else {
			curr = path.Join(curr, part)
		}
		info, err := store.root.Lstat(curr)
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return ErrSymlinkBlocked
		}
	}
	return nil
}

func validateRelativeTarget(relTargetPath string) (string, error) {
	cleaned := path.Clean(strings.ReplaceAll(strings.TrimSpace(relTargetPath), "\\", "/"))
	if cleaned == "" || cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "/") || strings.HasPrefix(cleaned, "../") {
		return "", ErrInvalidPath
	}
	if cleaned == StagingDirName || strings.HasPrefix(cleaned, StagingDirName+"/") {
		return "", ErrInvalidPath
	}
	return cleaned, nil
}

func hashReader(reader io.Reader) (int64, string, error) {
	hasher := sha256.New()
	size, err := io.Copy(hasher, reader)
	if err != nil {
		return 0, "", err
	}
	return size, hex.EncodeToString(hasher.Sum(nil)), nil
}
