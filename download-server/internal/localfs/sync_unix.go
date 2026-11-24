//go:build !windows

package localfs

func (store *Store) syncDir(dirRel string) error {
	dirFile, err := store.root.Open(dirRel)
	if err != nil {
		return err
	}
	defer dirFile.Close()
	return dirFile.Sync()
}
