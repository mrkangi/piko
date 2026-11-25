//go:build !windows

package config

import (
	"errors"
	"os"

	"golang.org/x/sys/unix"
)

func acquirePlatformLock(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_EX|unix.LOCK_NB)
}

func releasePlatformLock(file *os.File) error {
	return unix.Flock(int(file.Fd()), unix.LOCK_UN)
}

func isLockHeld(err error) bool {
	return errors.Is(err, unix.EWOULDBLOCK) || errors.Is(err, unix.EAGAIN)
}
