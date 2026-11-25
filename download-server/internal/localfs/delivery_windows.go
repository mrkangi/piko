//go:build windows

package localfs

func ignoreDirSyncUnsupported(err error) error {
	return err
}
