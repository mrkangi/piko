package updater

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
)

func backupExecutable(exe string) error {
	source, err := os.Open(exe)
	if err != nil {
		return err
	}
	defer source.Close()
	info, err := source.Stat()
	if err != nil {
		return err
	}

	backup := exe + ".prev"
	temp, err := os.CreateTemp(filepath.Dir(exe), ".download-server-backup-*")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	defer func() {
		_ = temp.Close()
		_ = os.Remove(tempPath)
	}()
	if _, err := io.Copy(temp, source); err != nil {
		return err
	}
	if err := temp.Chmod(info.Mode().Perm()); err != nil {
		return err
	}
	if err := temp.Sync(); err != nil {
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	_ = os.Remove(backup)
	if err := os.Rename(tempPath, backup); err != nil {
		return fmt.Errorf("rename backup: %w", err)
	}
	return nil
}
