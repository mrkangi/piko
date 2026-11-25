package logview

import (
	"io"
	"log"
	"os"
	"sync"
)

var (
	defaultOnce   sync.Once
	defaultBuffer *Buffer
)

// InstallDefault attaches one bounded buffer to the process-wide standard
// logger. Console output is kept, but write errors from stderr are ignored so
// they can never prevent the in-memory copy from being captured.
func InstallDefault() *Buffer {
	defaultOnce.Do(func() {
		defaultBuffer = New(DefaultMaxEntries, DefaultMaxBytes)
		log.SetOutput(fanout{buffer: defaultBuffer, console: os.Stderr})
	})
	return defaultBuffer
}

type fanout struct {
	buffer  *Buffer
	console io.Writer
}

func (f fanout) Write(p []byte) (int, error) {
	_, _ = f.buffer.Write(p)
	if f.console != nil {
		_, _ = f.console.Write(p)
	}
	return len(p), nil
}
