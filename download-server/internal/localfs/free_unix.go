//go:build !windows

package localfs

import (
	"fmt"

	"golang.org/x/sys/unix"
)

func (store *Store) FreeBytes() (uint64, error) {
	var stat unix.Statfs_t
	if err := unix.Statfs(store.rootPath, &stat); err != nil {
		return 0, fmt.Errorf("statfs download root: %w", err)
	}
	return uint64(stat.Bavail) * uint64(stat.Bsize), nil
}
