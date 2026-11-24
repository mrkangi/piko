package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
)

type InstanceLock struct {
	file *os.File
	path string
}

func AcquireInstanceLock(dataDir string) (*InstanceLock, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	lockPath := filepath.Join(dataDir, "server.lock")
	file, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open instance lock: %w", err)
	}
	if err := acquirePlatformLock(file); err != nil {
		_ = file.Close()
		if isLockHeld(err) {
			return nil, fmt.Errorf("another instance already holds %s", lockPath)
		}
		return nil, fmt.Errorf("acquire instance lock: %w", err)
	}
	_ = file.Truncate(0)
	_, _ = file.Seek(0, 0)
	_, _ = file.WriteString(strconv.Itoa(os.Getpid()) + "\n")
	_ = file.Sync()
	return &InstanceLock{file: file, path: lockPath}, nil
}

func (lock *InstanceLock) Close() error {
	if lock == nil || lock.file == nil {
		return nil
	}
	_ = releasePlatformLock(lock.file)
	err := lock.file.Close()
	lock.file = nil
	return err
}
