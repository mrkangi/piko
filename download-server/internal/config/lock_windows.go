//go:build windows

package config

import (
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

func acquirePlatformLock(file *os.File) error {
	overlapped := new(windows.Overlapped)
	return windows.LockFileEx(
		windows.Handle(file.Fd()),
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0,
		1,
		0,
		overlapped,
	)
}

func releasePlatformLock(file *os.File) error {
	overlapped := new(windows.Overlapped)
	return windows.UnlockFileEx(windows.Handle(file.Fd()), 0, 1, 0, overlapped)
}

func isLockHeld(err error) bool {
	return errors.Is(err, windows.ERROR_LOCK_VIOLATION) || errors.Is(err, windows.ERROR_IO_PENDING)
}
