package web

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/control"
	"github.com/nihildigit/piko/download-server/internal/download"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/notify"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

type setupStubClient struct {
	*backend.FakeBackend
}

func (setupStubClient) Verify(context.Context) error { return nil }

func TestAuthCSRFAndEndToEndWebFlow(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	fsStore, err := localfs.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer fsStore.Close()

	fake := backend.NewFakeBackend()
	fake.PutDir("d_movies", "", "Movies")
	fake.PutFile("f1", "d_movies", "movie.mkv", "G1", []byte("movie-bytes"), time.Now())

	broker := events.NewBroker()
	cat := catalog.NewService(st, fake, broker, "acct_1", 10*time.Minute, 5)
	dl := download.NewService(st, fsStore, fake, fake, broker, download.Options{MaxConcurrent: 1})
	adminHash, err := secret.HashPassword("admin-pass")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := st.CreateAdmin(ctx, "admin", adminHash); err != nil {
		t.Fatal(err)
	}
	secrets, err := secret.LoadOrCreate(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	runtime := backend.NewRuntime()
	runtime.Configure(fake, fake)
	controller := control.New(st, secrets, runtime, cat, dl, broker, dataDir)
	auth := NewAuthenticator(st, false, false)
	notifier := notify.NewManager(st, secrets, broker, nil)
	srv := NewServer(auth, st, cat, dl, broker, controller, notifier)
	srv.SetReady(true)

	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	// Unauthenticated request rejected.
	unauthResp, err := http.Get(httpSrv.URL + "/api/tasks")
	if err != nil {
		t.Fatal(err)
	}
	_ = unauthResp.Body.Close()
	if unauthResp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 unauthenticated, got %d", unauthResp.StatusCode)
	}

	// Healthz/Readyz accessible.
	readyResp, err := http.Get(httpSrv.URL + "/readyz")
	if err != nil || readyResp.StatusCode != http.StatusOK {
		t.Fatalf("expected /readyz 200, got %v %v", readyResp, err)
	}
	_ = readyResp.Body.Close()

	// Login.
	loginBody := `{"username":"admin","password":"admin-pass"}`
	loginResp, err := http.Post(httpSrv.URL+"/api/auth/login", "application/json", strings.NewReader(loginBody))
	if err != nil {
		t.Fatal(err)
	}
	defer loginResp.Body.Close()
	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 login, got %d", loginResp.StatusCode)
	}
	var loginPayload struct {
		CSRFToken string `json:"csrf_token"`
	}
	_ = json.NewDecoder(loginResp.Body).Decode(&loginPayload)
	cookies := loginResp.Cookies()

	// Missing CSRF rejected.
	noCsrfReq, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/api/sources", bytes.NewBufferString(`{"remote_root_id":"d_movies","local_alias":"Movies"}`))
	for _, c := range cookies {
		noCsrfReq.AddCookie(c)
	}
	noCsrfResp, err := http.DefaultClient.Do(noCsrfReq)
	if err != nil {
		t.Fatal(err)
	}
	_ = noCsrfResp.Body.Close()
	if noCsrfResp.StatusCode != http.StatusForbidden {
		t.Fatalf("expected 403 without CSRF token, got %d", noCsrfResp.StatusCode)
	}

	// Create source with CSRF.
	createReq, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/api/sources", bytes.NewBufferString(`{"remote_root_id":"d_movies","local_alias":"Movies","initial_mode":"download_existing"}`))
	createReq.Header.Set("Content-Type", "application/json")
	createReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		createReq.AddCookie(c)
	}
	createResp, err := http.DefaultClient.Do(createReq)
	if err != nil {
		t.Fatal(err)
	}
	defer createResp.Body.Close()
	if createResp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 create source, got %d", createResp.StatusCode)
	}
	var created store.Source
	_ = json.NewDecoder(createResp.Body).Decode(&created)

	if _, err := cat.ScanSource(ctx, created.ID); err != nil {
		t.Fatalf("ScanSource failed: %v", err)
	}
	tasksReq, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/tasks", nil)
	for _, c := range cookies {
		tasksReq.AddCookie(c)
	}
	tasksResp, err := http.DefaultClient.Do(tasksReq)
	if err != nil || tasksResp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/tasks failed: %v", err)
	}
	var tasksBody struct {
		Tasks []download.TaskView `json:"tasks"`
	}
	_ = json.NewDecoder(tasksResp.Body).Decode(&tasksBody)
	_ = tasksResp.Body.Close()
	if len(tasksBody.Tasks) != 1 || tasksBody.Tasks[0].TargetRelPath != "Movies/movie.mkv" {
		t.Fatalf("unexpected tasks list: %+v", tasksBody.Tasks)
	}

	scanAllReq, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/api/sources/scan", bytes.NewBufferString(`{}`))
	scanAllReq.Header.Set("Content-Type", "application/json")
	scanAllReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		scanAllReq.AddCookie(c)
	}
	scanAllResp, err := http.DefaultClient.Do(scanAllReq)
	if err != nil || scanAllResp.StatusCode != http.StatusAccepted {
		t.Fatalf("POST /api/sources/scan failed: %v %v", scanAllResp, err)
	}
	_ = scanAllResp.Body.Close()

	// Search filter & batch pause.
	searchReq, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/tasks?q=nomatch", nil)
	for _, c := range cookies {
		searchReq.AddCookie(c)
	}
	searchResp, err := http.DefaultClient.Do(searchReq)
	if err != nil {
		t.Fatal(err)
	}
	var searchBody struct {
		Tasks []download.TaskView `json:"tasks"`
		Total int                 `json:"total"`
	}
	_ = json.NewDecoder(searchResp.Body).Decode(&searchBody)
	_ = searchResp.Body.Close()
	if searchBody.Total != 0 || len(searchBody.Tasks) != 0 {
		t.Fatalf("expected 0 tasks on unmatched search, got %+v", searchBody)
	}

	batchReq, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/api/tasks/batch", bytes.NewBufferString(`{"action":"pause"}`))
	batchReq.Header.Set("Content-Type", "application/json")
	batchReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		batchReq.AddCookie(c)
	}
	batchResp, err := http.DefaultClient.Do(batchReq)
	if err != nil || batchResp.StatusCode != http.StatusAccepted {
		t.Fatalf("batch pause failed: %v %v", batchResp, err)
	}
	_ = batchResp.Body.Close()

	// SPA fallback serves HTML, unknown /api/* returns JSON 404.
	spaResp, err := http.Get(httpSrv.URL + "/tasks")
	if err != nil || spaResp.StatusCode != http.StatusOK || !strings.Contains(spaResp.Header.Get("Content-Type"), "text/html") {
		t.Fatalf("expected SPA HTML on /tasks, got %v %v", spaResp, err)
	}
	_ = spaResp.Body.Close()

	api404Resp, err := http.Get(httpSrv.URL + "/api/unknown")
	if err != nil || api404Resp.StatusCode != http.StatusNotFound {
		t.Fatalf("expected 404 on unknown API route, got %v %v", api404Resp, err)
	}
	_ = api404Resp.Body.Close()

	// Settings read/update, local-dirs browse, and source delete.
	nextDownloadDir := t.TempDir()
	putSettingsReq, _ := http.NewRequest(http.MethodPut, httpSrv.URL+"/api/system/settings", bytes.NewBufferString(`{"download_dir":`+jsonQuote(nextDownloadDir)+`,"max_concurrent_downloads":4,"segment_threads":6,"segment_min_size_mib":8,"default_scan_interval_sec":120,"min_free_space_mib":256}`))
	putSettingsReq.Header.Set("Content-Type", "application/json")
	putSettingsReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		putSettingsReq.AddCookie(c)
	}
	putSettingsResp, err := http.DefaultClient.Do(putSettingsReq)
	if err != nil || putSettingsResp.StatusCode != http.StatusOK {
		t.Fatalf("PUT /api/system/settings failed: %v %v", putSettingsResp, err)
	}
	var updatedSettings systemSettingsPayload
	_ = json.NewDecoder(putSettingsResp.Body).Decode(&updatedSettings)
	_ = putSettingsResp.Body.Close()
	if updatedSettings.DownloadDir != nextDownloadDir ||
		updatedSettings.MaxConcurrentDownloads != 4 ||
		updatedSettings.SegmentThreads != 6 ||
		updatedSettings.SegmentMinSizeMiB != 8 ||
		updatedSettings.DefaultScanIntervalSec != 120 {
		t.Fatalf("unexpected updated settings: %+v", updatedSettings)
	}

	localDirsReq, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/system/local-dirs?path="+nextDownloadDir, nil)
	for _, c := range cookies {
		localDirsReq.AddCookie(c)
	}
	localDirsResp, err := http.DefaultClient.Do(localDirsReq)
	if err != nil || localDirsResp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/system/local-dirs failed: %v %v", localDirsResp, err)
	}
	_ = localDirsResp.Body.Close()

	delReq, _ := http.NewRequest(http.MethodDelete, httpSrv.URL+"/api/sources/"+created.ID, nil)
	delReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		delReq.AddCookie(c)
	}
	delResp, err := http.DefaultClient.Do(delReq)
	if err != nil || delResp.StatusCode != http.StatusOK {
		t.Fatalf("DELETE /api/sources failed: %v %v", delResp, err)
	}
	_ = delResp.Body.Close()

	notifyCreateReq, _ := http.NewRequest(http.MethodPost, httpSrv.URL+"/api/notifications", bytes.NewBufferString(`{"name":"Ops","type":"webhook","url":"https://example.com/hook","events":["task.failed"],"enabled":true}`))
	notifyCreateReq.Header.Set("Content-Type", "application/json")
	notifyCreateReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		notifyCreateReq.AddCookie(c)
	}
	notifyCreateResp, err := http.DefaultClient.Do(notifyCreateReq)
	if err != nil || notifyCreateResp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /api/notifications failed: %v %v", notifyCreateResp, err)
	}
	var notifyChannel struct {
		ID string `json:"id"`
	}
	_ = json.NewDecoder(notifyCreateResp.Body).Decode(&notifyChannel)
	_ = notifyCreateResp.Body.Close()
	if notifyChannel.ID == "" {
		t.Fatal("notification channel id missing")
	}
	notifyListReq, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/notifications", nil)
	for _, c := range cookies {
		notifyListReq.AddCookie(c)
	}
	notifyListResp, err := http.DefaultClient.Do(notifyListReq)
	if err != nil || notifyListResp.StatusCode != http.StatusOK {
		t.Fatalf("GET /api/notifications failed: %v %v", notifyListResp, err)
	}
	_ = notifyListResp.Body.Close()
	notifyDeleteReq, _ := http.NewRequest(http.MethodDelete, httpSrv.URL+"/api/notifications/"+notifyChannel.ID, nil)
	notifyDeleteReq.Header.Set("X-CSRF-Token", loginPayload.CSRFToken)
	for _, c := range cookies {
		notifyDeleteReq.AddCookie(c)
	}
	notifyDeleteResp, err := http.DefaultClient.Do(notifyDeleteReq)
	if err != nil || notifyDeleteResp.StatusCode != http.StatusOK {
		t.Fatalf("DELETE /api/notifications failed: %v %v", notifyDeleteResp, err)
	}
	_ = notifyDeleteResp.Body.Close()
}

func jsonQuote(v string) string {
	b, _ := json.Marshal(v)
	return string(b)
}

func TestSetupEndpointCreatesFirstAdminAndSession(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	fsStore, err := localfs.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer fsStore.Close()
	secrets, err := secret.LoadOrCreate(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	broker := events.NewBroker()
	runtime := backend.NewRuntime()
	fake := backend.NewFakeBackend()
	cat := catalog.NewService(st, runtime, broker, "", 5*time.Minute, 5)
	dl := download.NewService(st, fsStore, runtime, runtime, broker, download.Options{MaxConcurrent: 1})
	controller := control.New(st, secrets, runtime, cat, dl, broker, dataDir)
	controller.SetClientFactory(func(username, password string) (control.RemoteClient, string, error) {
		return setupStubClient{FakeBackend: fake}, "acct_setup_test", nil
	})
	token, err := controller.EnsureSetupToken(ctx)
	if err != nil {
		t.Fatal(err)
	}
	notifier := notify.NewManager(st, secrets, broker, nil)
	srv := NewServer(NewAuthenticator(st, false, false), st, cat, dl, broker, controller, notifier, Options{DataDir: dataDir})
	srv.SetReady(true)
	httpSrv := httptest.NewServer(srv.Handler())
	defer httpSrv.Close()

	statusResp, err := http.Get(httpSrv.URL + "/api/setup/status")
	if err != nil || statusResp.StatusCode != http.StatusOK {
		t.Fatalf("setup status failed: %v %v", statusResp, err)
	}
	var before map[string]any
	_ = json.NewDecoder(statusResp.Body).Decode(&before)
	_ = statusResp.Body.Close()
	if before["needs_setup"] != true {
		t.Fatalf("expected needs_setup=true, got %+v", before)
	}

	downloadDir := t.TempDir()
	payload, _ := json.Marshal(map[string]any{
		"token":           token,
		"admin_username":  "admin",
		"admin_password":  "password-123",
		"pikpak_username": "user@example.com",
		"pikpak_password": "pikpak-pass",
		"download_dir":    downloadDir,
	})
	setupResp, err := http.Post(httpSrv.URL+"/api/setup", "application/json", bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	defer setupResp.Body.Close()
	if setupResp.StatusCode != http.StatusOK {
		t.Fatalf("setup endpoint failed: %d", setupResp.StatusCode)
	}
	cookies := setupResp.Cookies()
	var setupPayload struct {
		CSRFToken string `json:"csrf_token"`
	}
	_ = json.NewDecoder(setupResp.Body).Decode(&setupPayload)
	if setupPayload.CSRFToken == "" || len(cookies) == 0 {
		t.Fatalf("expected setup session cookie and CSRF token")
	}

	meReq, _ := http.NewRequest(http.MethodGet, httpSrv.URL+"/api/auth/me", nil)
	for _, cookie := range cookies {
		meReq.AddCookie(cookie)
	}
	meResp, err := http.DefaultClient.Do(meReq)
	if err != nil || meResp.StatusCode != http.StatusOK {
		t.Fatalf("expected setup session to authenticate: %v %v", meResp, err)
	}
	_ = meResp.Body.Close()
}
