//go:build !windows

package updater

import (
	"os"
	"syscall"
)

func replaceExecutable(exe, tempPath string) error {
	return os.Rename(tempPath, exe)
}

func restartProcess(exe string) error {
	argv := append([]string{exe}, os.Args[1:]...)
	return syscall.Exec(exe, argv, os.Environ())
}

func syncDir(dir string) {
	handle, err := os.Open(dir)
	if err != nil {
		return
	}
	_ = handle.Sync()
	_ = handle.Close()
}
