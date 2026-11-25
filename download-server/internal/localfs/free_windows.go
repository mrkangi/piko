//go:build windows

package localfs

import (
	"fmt"

	"golang.org/x/sys/windows"
)

func (store *Store) FreeBytes() (uint64, error) {
	path, err := windows.UTF16PtrFromString(store.rootPath)
	if err != nil {
		return 0, fmt.Errorf("encode download root: %w", err)
	}
	var freeToCaller uint64
	var total uint64
	var totalFree uint64
	if err := windows.GetDiskFreeSpaceEx(path, &freeToCaller, &total, &totalFree); err != nil {
		return 0, fmt.Errorf("get disk free space: %w", err)
	}
	return totalFree, nil
}
