package config

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	DefaultHost               = "0.0.0.0"
	DefaultPort               = 7788
	DefaultMaxConcurrent      = 2
	DefaultScanInterval       = 5 * time.Minute
	DefaultMinFreeSpaceBytes  = 512 * 1024 * 1024
	DefaultNoProgressTimeout  = 5 * time.Minute
	DefaultMaxRetries         = 5
	DefaultAPIRatePerSecond   = 5
	DefaultAPIRateBurst       = 10
	DefaultDownloadSubdirName = "PikoDownloads"
	DefaultDataSubdirName     = ".pikpak/downloader"
)

type Config struct {
	ListenAddr  string
	DataDir     string
	DownloadDir string
}

func LoadFromEnv() (Config, error) {
	host := strings.TrimSpace(os.Getenv("DOWNLOAD_SERVER_HOST"))
	if host == "" {
		host = DefaultHost
	}
	port := DefaultPort
	if raw := strings.TrimSpace(os.Getenv("DOWNLOAD_SERVER_PORT")); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil || parsed < 1 || parsed > 65535 {
			return Config{}, fmt.Errorf("DOWNLOAD_SERVER_PORT must be between 1 and 65535")
		}
		port = parsed
	}

	home, err := os.UserHomeDir()
	if err != nil || strings.TrimSpace(home) == "" {
		home = "."
	}
	dataDir := strings.TrimSpace(os.Getenv("DOWNLOAD_SERVER_DATA_DIR"))
	if dataDir == "" {
		dataDir = filepath.Join(home, filepath.FromSlash(DefaultDataSubdirName))
	}
	dataDir = expandHome(dataDir, home)

	cfg := Config{
		ListenAddr:  fmt.Sprintf("%s:%d", host, port),
		DataDir:     dataDir,
		DownloadDir: filepath.Join(home, DefaultDownloadSubdirName),
	}
	if err := cfg.Validate(home); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

func (cfg *Config) Validate(home string) error {
	if strings.TrimSpace(cfg.ListenAddr) == "" {
		return errors.New("listen address is required")
	}
	if strings.TrimSpace(cfg.DataDir) == "" {
		return errors.New("data directory is required")
	}
	absData, err := filepath.Abs(cfg.DataDir)
	if err != nil {
		return fmt.Errorf("resolve data directory: %w", err)
	}
	if strings.TrimSpace(cfg.DownloadDir) == "" {
		cfg.DownloadDir = filepath.Join(home, DefaultDownloadSubdirName)
	}
	absDownload, err := filepath.Abs(cfg.DownloadDir)
	if err != nil {
		return fmt.Errorf("resolve download directory: %w", err)
	}
	if absData == absDownload {
		return errors.New("data directory and download directory must be distinct")
	}
	cfg.DataDir = absData
	cfg.DownloadDir = absDownload
	return nil
}

func expandHome(raw string, home string) string {
	if raw == "~" {
		return home
	}
	if strings.HasPrefix(raw, "~/") {
		return filepath.Join(home, filepath.FromSlash(strings.TrimPrefix(raw, "~/")))
	}
	return raw
}

func AccountIDFor(username string) string {
	trimmed := strings.ToLower(strings.TrimSpace(username))
	if trimmed == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(trimmed))
	return "acct_" + hex.EncodeToString(sum[:8])
}
