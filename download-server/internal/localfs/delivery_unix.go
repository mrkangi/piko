//go:build !windows

package localfs

import (
	"errors"
	"syscall"
)

// ignoreDirSyncUnsupported 忽略文件系统不支持目录 fsync 的错误。内容本身
// 已 fsync 且改名是原子的，目录项持久化交给后续正常卸载/扫描。
func ignoreDirSyncUnsupported(err error) error {
	if err == nil {
		return nil
	}
	for _, target := range []error{
		syscall.EINVAL,
		syscall.ENOTSUP,
		syscall.EOPNOTSUPP,
		syscall.ENOSYS,
		syscall.EBADF,
	} {
		if errors.Is(err, target) {
			return nil
		}
	}
	return err
}
