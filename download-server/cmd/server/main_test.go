package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/config"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

func TestM5TwoSourcesRecursiveDownloadSoftDeleteAndRestart(t *testing.T) {
	dataDir := t.TempDir()
	downloadDir := t.TempDir()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_movies", "", "Movies")
	fake.PutDir("d_shows", "", "Shows")
	fake.PutDir("d_s1", "d_shows", "Season 1")
	fake.PutFile("f_old", "d_movies", "existing.mkv", "G_OLD", []byte("old-movie"), time.Now())
	fake.PutFile("f_s1e1", "d_s1", "ep1.mkv", "G_E1", []byte("episode-one-payload"), time.Now())

	cfg := config.Config{
		ListenAddr:  addr,
		DataDir:     dataDir,
		DownloadDir: downloadDir,
	}
	preparedStore, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	adminHash, err := secret.HashPassword("smoke-pass")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := preparedStore.CreateAdmin(context.Background(), "admin", adminHash); err != nil {
		t.Fatal(err)
	}
	_ = preparedStore.Close()

	ctx1, cancel1 := context.WithCancel(context.Background())
	errCh1 := make(chan error, 1)
	go func() { errCh1 <- runServer(ctx1, cfg, fake, fake) }()

	baseURL := "http://" + addr
	waitReady(t, baseURL)

	client := &http.Client{Timeout: 5 * time.Second}
	cookies, csrf := loginAPI(t, client, baseURL, "admin", "smoke-pass")

	srcMovies := postJSON(t, client, baseURL+"/api/sources", cookies, csrf, map[string]any{
		"remote_root_id": "d_movies",
		"local_alias":    "Movies",
		"initial_mode":   "monitor_new_only",
	})
	_ = srcMovies
	srcShows := postJSON(t, client, baseURL+"/api/sources", cookies, csrf, map[string]any{
		"remote_root_id": "d_shows",
		"local_alias":    "Shows",
		"initial_mode":   "download_existing",
	})

	waitForFile(t, filepath.Join(downloadDir, "Shows", "Season 1", "ep1.mkv"), []byte("episode-one-payload"))
	if _, err := os.Stat(filepath.Join(downloadDir, "Movies", "existing.mkv")); !os.IsNotExist(err) {
		t.Fatalf("monitor_new_only should not download pre-existing file")
	}

	// Add new nested file to Movies and trigger scan.
	fake.PutDir("d_2026", "d_movies", "2026")
	fake.PutFile("f_new", "d_2026", "fresh.mkv", "G_NEW", []byte("fresh-movie"), time.Now())
	srcMoviesID := srcMovies["id"].(string)
	_ = postJSON(t, client, fmt.Sprintf("%s/api/sources/%s/scan", baseURL, srcMoviesID), cookies, csrf, nil)
	waitForFile(t, filepath.Join(downloadDir, "Movies", "2026", "fresh.mkv"), []byte("fresh-movie"))

	// Remove f_s1e1 remotely and trigger 2 scans -> marked OUT_OF_SCOPE while local file remains intact.
	fake.Remove("f_s1e1")
	srcShowsID := srcShows["id"].(string)
	_ = postJSON(t, client, fmt.Sprintf("%s/api/sources/%s/scan", baseURL, srcShowsID), cookies, csrf, nil)
	time.Sleep(150 * time.Millisecond)
	_ = postJSON(t, client, fmt.Sprintf("%s/api/sources/%s/scan", baseURL, srcShowsID), cookies, csrf, nil)

	deadline := time.Now().Add(4 * time.Second)
	foundMissing := false
	for time.Now().Before(deadline) {
		filesResp := getJSON(t, client, baseURL+"/api/files?remote_state=OUT_OF_SCOPE", cookies)
		items := filesResp["files"].([]any)
		if len(items) == 1 {
			foundMissing = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !foundMissing {
		t.Fatalf("expected f_s1e1 to be marked OUT_OF_SCOPE after two scans")
	}
	if raw, err := os.ReadFile(filepath.Join(downloadDir, "Shows", "Season 1", "ep1.mkv")); err != nil || string(raw) != "episode-one-payload" {
		t.Fatalf("local delivered file must remain untouched after remote removal")
	}

	cancel1()
	if err := <-errCh1; err != nil {
		t.Fatalf("server1 shutdown error: %v", err)
	}

	// Restart server on same dataDir and downloadDir and verify state persists.
	ctx2, cancel2 := context.WithCancel(context.Background())
	defer cancel2()
	errCh2 := make(chan error, 1)
	go func() { errCh2 <- runServer(ctx2, cfg, fake, fake) }()
	waitReady(t, baseURL)

	filesAfterRestart := getJSON(t, client, baseURL+"/api/files?remote_state=OUT_OF_SCOPE", cookies)
	if len(filesAfterRestart["files"].([]any)) != 1 {
		t.Fatalf("expected persisted OUT_OF_SCOPE record after restart")
	}

	// Update download directory via Web API, restart server, and verify new directory persists.
	newDownloadDir := t.TempDir()
	putReq, _ := http.NewRequest(http.MethodPut, baseURL+"/api/system/settings", bytes.NewBufferString(fmt.Sprintf(`{"download_dir":%q,"max_concurrent_downloads":3}`, newDownloadDir)))
	putReq.Header.Set("Content-Type", "application/json")
	putReq.Header.Set("X-CSRF-Token", csrf)
	for _, c := range cookies {
		putReq.AddCookie(c)
	}
	putResp, err := client.Do(putReq)
	if err != nil || putResp.StatusCode != http.StatusOK {
		t.Fatalf("PUT /api/system/settings failed: %v %v", putResp, err)
	}
	_ = putResp.Body.Close()

	cancel2()
	if err := <-errCh2; err != nil {
		t.Fatalf("server2 shutdown error: %v", err)
	}

	ctx3, cancel3 := context.WithCancel(context.Background())
	defer cancel3()
	errCh3 := make(chan error, 1)
	go func() { errCh3 <- runServer(ctx3, cfg, fake, fake) }()
	waitReady(t, baseURL)
	settingsAfterRestart := getJSON(t, client, baseURL+"/api/system/settings", cookies)
	if settingsAfterRestart["download_dir"] != newDownloadDir {
		t.Fatalf("expected persisted download_dir %q after restart, got %v", newDownloadDir, settingsAfterRestart["download_dir"])
	}
	cancel3()
	if err := <-errCh3; err != nil {
		t.Fatalf("server3 shutdown error: %v", err)
	}
}

func waitReady(t *testing.T, baseURL string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(baseURL + "/readyz")
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(30 * time.Millisecond)
	}
	t.Fatalf("server did not become ready")
}

func loginAPI(t *testing.T, client *http.Client, baseURL, user, pass string) ([]*http.Cookie, string) {
	t.Helper()
	resp, err := client.Post(baseURL+"/api/auth/login", "application/json", strings.NewReader(fmt.Sprintf(`{"username":%q,"password":%q}`, user, pass)))
	if err != nil || resp.StatusCode != http.StatusOK {
		t.Fatalf("login failed: %v %v", resp, err)
	}
	defer resp.Body.Close()
	var body map[string]string
	_ = json.NewDecoder(resp.Body).Decode(&body)
	return resp.Cookies(), body["csrf_token"]
}

func postJSON(t *testing.T, client *http.Client, url string, cookies []*http.Cookie, csrf string, payload any) map[string]any {
	t.Helper()
	var raw []byte
	if payload != nil {
		raw, _ = json.Marshal(payload)
	}
	req, _ := http.NewRequest(http.MethodPost, url, bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-CSRF-Token", csrf)
	for _, c := range cookies {
		req.AddCookie(c)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("POST %s failed: %v", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		t.Fatalf("POST %s unexpected status %d", url, resp.StatusCode)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out
}

func getJSON(t *testing.T, client *http.Client, url string, cookies []*http.Cookie) map[string]any {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	for _, c := range cookies {
		req.AddCookie(c)
	}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("GET %s failed: %v", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		t.Fatalf("GET %s unexpected status %d", url, resp.StatusCode)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return out
}

func waitForFile(t *testing.T, path string, want []byte) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if raw, err := os.ReadFile(path); err == nil && bytes.Equal(raw, want) {
			return
		}
		time.Sleep(30 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for file %s", path)
}
