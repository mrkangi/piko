//go:build !embed_ui

package web

import (
	"embed"
	"io/fs"
	"os"
	"path/filepath"
)

//go:embed fallback/index.html
var fallbackFS embed.FS

func resolveSPAFilesystem(staticDir string) fs.FS {
	if staticDir != "" {
		if info, err := os.Stat(staticDir); err == nil && info.IsDir() {
			return os.DirFS(staticDir)
		}
	}
	for _, candidate := range []string{
		filepath.Join("internal", "web", "dist"),
		filepath.Join("web", "dist"),
		"dist",
	} {
		if info, err := os.Stat(candidate); err == nil && info.IsDir() {
			if _, idxErr := os.Stat(filepath.Join(candidate, "index.html")); idxErr == nil {
				return os.DirFS(candidate)
			}
		}
	}
	sub, _ := fs.Sub(fallbackFS, "fallback")
	return sub
}
