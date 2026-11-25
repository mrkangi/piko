//go:build embed_ui

package web

import (
	"embed"
	"io/fs"
	"os"
)

//go:embed all:dist
var embeddedDistFS embed.FS

func resolveSPAFilesystem(staticDir string) fs.FS {
	if staticDir != "" {
		if info, err := os.Stat(staticDir); err == nil && info.IsDir() {
			return os.DirFS(staticDir)
		}
	}
	sub, _ := fs.Sub(embeddedDistFS, "dist")
	return sub
}
