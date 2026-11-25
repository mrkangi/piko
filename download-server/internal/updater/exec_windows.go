//go:build windows

package updater

import (
	"os"
	"os/exec"
)

func replaceExecutable(exe, tempPath string) error {
	backup := exe + ".prev"
	_ = os.Remove(backup)
	if err := os.Rename(exe, backup); err != nil {
		return err
	}
	if err := os.Rename(tempPath, exe); err != nil {
		_ = os.Rename(backup, exe)
		return err
	}
	return nil
}

func restartProcess(exe string) error {
	cmd := exec.Command(exe, os.Args[1:]...)
	cmd.Env = os.Environ()
	cmd.Stdin = os.Stdin
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	os.Exit(0)
	return nil
}

func syncDir(string) {}
