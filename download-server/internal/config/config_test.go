package config

import (
	"path/filepath"
	"testing"
)

func TestLoadDefaultsFromHome(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("DOWNLOAD_SERVER_HOST", "")
	t.Setenv("DOWNLOAD_SERVER_PORT", "")
	t.Setenv("DOWNLOAD_SERVER_DATA_DIR", "")

	cfg, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("LoadFromEnv failed: %v", err)
	}
	if cfg.ListenAddr != "0.0.0.0:7788" {
		t.Fatalf("unexpected listen address: %s", cfg.ListenAddr)
	}
	if cfg.DataDir != filepath.Join(home, ".pikpak", "downloader") {
		t.Fatalf("unexpected default data dir: %s", cfg.DataDir)
	}
	if cfg.DownloadDir != filepath.Join(home, "PikoDownloads") {
		t.Fatalf("unexpected default download dir: %s", cfg.DownloadDir)
	}
}

func TestLoadHostPortAndDataDirOverrides(t *testing.T) {
	home := t.TempDir()
	customData := filepath.Join(t.TempDir(), "data")
	t.Setenv("HOME", home)
	t.Setenv("DOWNLOAD_SERVER_HOST", "127.0.0.1")
	t.Setenv("DOWNLOAD_SERVER_PORT", "17788")
	t.Setenv("DOWNLOAD_SERVER_DATA_DIR", customData)

	cfg, err := LoadFromEnv()
	if err != nil {
		t.Fatalf("LoadFromEnv failed: %v", err)
	}
	if cfg.ListenAddr != "127.0.0.1:17788" {
		t.Fatalf("unexpected listen address: %s", cfg.ListenAddr)
	}
	absData, _ := filepath.Abs(customData)
	if cfg.DataDir != absData {
		t.Fatalf("unexpected data dir: %s", cfg.DataDir)
	}
}

func TestLoadRejectsInvalidPort(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("DOWNLOAD_SERVER_PORT", "70000")
	if _, err := LoadFromEnv(); err == nil {
		t.Fatal("expected invalid port error")
	}
}

func TestAcquireInstanceLockRejectsSecondInstance(t *testing.T) {
	dataDir := t.TempDir()
	first, err := AcquireInstanceLock(dataDir)
	if err != nil {
		t.Fatalf("first lock failed: %v", err)
	}
	defer first.Close()

	if second, err := AcquireInstanceLock(dataDir); err == nil {
		_ = second.Close()
		t.Fatalf("expected second lock to fail")
	}
}
