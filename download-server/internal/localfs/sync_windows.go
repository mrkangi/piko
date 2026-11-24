//go:build windows

package localfs

func (store *Store) syncDir(string) error {
	return nil
}
