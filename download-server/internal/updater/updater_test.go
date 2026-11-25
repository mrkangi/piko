package updater

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestVersionFromAsset(t *testing.T) {
	tests := []struct {
		name   string
		asset  string
		suffix string
		ext    string
		want   string
	}{
		{"linux", "download-server-1.6.0-linux-amd64", "-linux-amd64", "", "1.6.0"},
		{"test", "download-server-9.9.9-20261125-test-server-linux-arm64", "-linux-arm64", "", "9.9.9-20261125-test-server"},
		{"windows", "download-server-1.6.0-windows-amd64.exe", "-windows-amd64", ".exe", "1.6.0"},
		{"wrong platform", "download-server-1.6.0-darwin-arm64", "-linux-amd64", "", ""},
		{"installer", "download-server-install-termux.sh", "-android-arm64", "", ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := versionFromAsset(test.asset, test.suffix, test.ext); got != test.want {
				t.Fatalf("versionFromAsset(%q) = %q, want %q", test.asset, got, test.want)
			}
		})
	}
}

func TestParseChecksums(t *testing.T) {
	sum := strings.Repeat("a", 64)
	raw := sum + "  download-server-1.0.0-linux-amd64\n" +
		sum + " *windows.exe\n" +
		"bad line\n" +
		"short  ignored\n"
	got := parseChecksums(raw)
	if got["download-server-1.0.0-linux-amd64"] != sum {
		t.Fatalf("linux checksum missing: %+v", got)
	}
	if got["windows.exe"] != sum {
		t.Fatalf("binary-mode checksum missing: %+v", got)
	}
	if len(got) != 2 {
		t.Fatalf("unexpected checksum count: %+v", got)
	}
}

func TestCheckPicksNewerRelease(t *testing.T) {
	suffix, ext, ok := platformSuffix(runtime.GOOS, runtime.GOARCH, goARM())
	if !ok {
		t.Skip("unsupported test platform")
	}
	currentAsset := "download-server-1.0.0" + suffix + ext
	latestAsset := "download-server-2.0.0" + suffix + ext
	api := newReleaseAPI(t, []map[string]any{
		releaseJSON("v1.0.0", false, "2026-01-01T00:00:00Z", currentAsset),
		releaseJSON("v2.0.0", false, "2026-01-20T00:00:00Z", latestAsset),
	})

	svc := newTestService(api.URL, "1.0.0", "2026-01-10T00:00:00Z")
	info, err := svc.Check(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if !info.Available || !info.CanApply {
		t.Fatalf("expected available update, got %+v", info)
	}
	if info.LatestVersion != "2.0.0" || info.LatestTag != "v2.0.0" {
		t.Fatalf("unexpected latest release: %+v", info)
	}
}

func TestCheckStableChannelSkipsPrerelease(t *testing.T) {
	suffix, ext, ok := platformSuffix(runtime.GOOS, runtime.GOARCH, goARM())
	if !ok {
		t.Skip("unsupported test platform")
	}
	api := newReleaseAPI(t, []map[string]any{
		releaseJSON("v2.0.0", false, "2026-01-20T00:00:00Z", "download-server-2.0.0"+suffix+ext),
		releaseJSON("test-20260125", true, "2026-01-25T00:00:00Z", "download-server-3.0.0-test"+suffix+ext),
	})

	svc := newTestService(api.URL, "1.0.0", "2026-01-10T00:00:00Z")
	info, err := svc.Check(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if !info.Available || info.LatestVersion != "2.0.0" {
		t.Fatalf("stable channel should ignore prerelease, got %+v", info)
	}
}

func TestCheckTestChannelIncludesPrerelease(t *testing.T) {
	suffix, ext, ok := platformSuffix(runtime.GOOS, runtime.GOARCH, goARM())
	if !ok {
		t.Skip("unsupported test platform")
	}
	api := newReleaseAPI(t, []map[string]any{
		releaseJSON("v2.0.0", false, "2026-01-20T00:00:00Z", "download-server-2.0.0"+suffix+ext),
		releaseJSON("test-20260125", true, "2026-01-25T00:00:00Z", "download-server-2.1.0-test"+suffix+ext),
	})

	svc := newTestService(api.URL, "2.0.0-test", "2026-01-10T00:00:00Z")
	info, err := svc.Check(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if !info.Available || info.LatestVersion != "2.1.0-test" {
		t.Fatalf("test channel should include prerelease, got %+v", info)
	}
}

func TestCheckSameVersionIsNotAnUpdate(t *testing.T) {
	suffix, ext, ok := platformSuffix(runtime.GOOS, runtime.GOARCH, goARM())
	if !ok {
		t.Skip("unsupported test platform")
	}
	api := newReleaseAPI(t, []map[string]any{
		releaseJSON("v2.0.0", false, "2026-01-20T00:00:00Z", "download-server-2.0.0"+suffix+ext),
	})

	svc := newTestService(api.URL, "2.0.0", "2026-01-10T00:00:00Z")
	info, err := svc.Check(context.Background(), false)
	if err != nil {
		t.Fatal(err)
	}
	if info.Available {
		t.Fatalf("same version must not be offered as update: %+v", info)
	}
	if info.Reason != "已是最新版本" {
		t.Fatalf("unexpected reason: %q", info.Reason)
	}
}

func TestDownloadAndReplace(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "download-server")
	if err := os.WriteFile(exe, []byte("old-binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	newBinary := make([]byte, minBinaryBytes+123)
	for i := range newBinary {
		newBinary[i] = byte(i % 251)
	}
	sum := sha256.Sum256(newBinary)
	sumHex := hex.EncodeToString(sum[:])

	mux := http.NewServeMux()
	mux.HandleFunc("/binary", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write(newBinary)
	})
	mux.HandleFunc("/SHA256SUMS.txt", func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintf(w, "%s  %s\n", sumHex, "download-server-2.0.0-test-linux-amd64")
	})
	server := httptest.NewServer(mux)
	defer server.Close()

	svc := newTestService(server.URL, "1.0.0", "2026-01-01T00:00:00Z")
	err := svc.downloadAndReplace(context.Background(), exe, Info{
		AssetName:   "download-server-2.0.0-test-linux-amd64",
		assetURL:    server.URL + "/binary",
		checksumURL: server.URL + "/SHA256SUMS.txt",
	})
	if err != nil {
		t.Fatal(err)
	}

	got, err := os.ReadFile(exe)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(newBinary) {
		t.Fatal("executable was not replaced with the downloaded binary")
	}
	backup, err := os.ReadFile(exe + ".prev")
	if err != nil {
		t.Fatal(err)
	}
	if string(backup) != "old-binary" {
		t.Fatalf("unexpected backup contents: %q", backup)
	}
}

func TestCompareVersions(t *testing.T) {
	tests := []struct {
		left, right string
		want        int
	}{
		{"1.2.0", "1.1.9", 1},
		{"v1.2.0", "1.2.0", 0},
		{"1.2.0", "1.2.0-1", 1},
		{"1.2.0-alpha", "1.2.0-beta", -1},
	}
	for _, test := range tests {
		if got := compareVersions(test.left, test.right); got != test.want {
			t.Fatalf("compareVersions(%q, %q) = %d, want %d", test.left, test.right, got, test.want)
		}
	}
}

func newTestService(apiBase, version, buildTime string) *Service {
	svc := New(version, buildTime, "owner/repo")
	svc.apiBase = apiBase
	svc.client = &http.Client{Timeout: 5 * time.Second}
	svc.container = func() bool { return false }
	return svc
}

func newReleaseAPI(t *testing.T, releases []map[string]any) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/owner/repo/releases", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(releases); err != nil {
			t.Errorf("encode releases: %v", err)
		}
	})
	server := httptest.NewServer(mux)
	t.Cleanup(server.Close)
	return server
}

func releaseJSON(tag string, prerelease bool, publishedAt, assetName string) map[string]any {
	base := "https://example.test/releases/" + tag
	return map[string]any{
		"tag_name":     tag,
		"name":         tag,
		"body":         "release notes",
		"draft":        false,
		"prerelease":   prerelease,
		"published_at": publishedAt,
		"created_at":   publishedAt,
		"html_url":     "https://example.test/" + tag,
		"assets": []map[string]any{
			{
				"name":                 assetName,
				"size":                 42,
				"browser_download_url": base + "/" + assetName,
			},
			{
				"name":                 checksumAssetName,
				"size":                 100,
				"browser_download_url": base + "/" + checksumAssetName,
			},
		},
	}
}
