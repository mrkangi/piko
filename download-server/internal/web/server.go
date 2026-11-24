package web

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/control"
	"github.com/nihildigit/piko/download-server/internal/download"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/notify"
	"github.com/nihildigit/piko/download-server/internal/store"
)

type Options struct {
	StaticDir  string
	CORSOrigin string
	DataDir    string
}

type Server struct {
	auth       *Authenticator
	store      *store.Store
	catalog    *catalog.Service
	download   *download.Service
	broker     *events.Broker
	controller *control.Controller
	notifier   *notify.Manager
	corsOrigin string
	dataDir    string
	spaFS      fs.FS
	ready      atomic.Bool
	mux        *http.ServeMux
}

func NewServer(
	auth *Authenticator,
	st *store.Store,
	cat *catalog.Service,
	dl *download.Service,
	broker *events.Broker,
	controller *control.Controller,
	notifier *notify.Manager,
	opts ...Options,
) *Server {
	var opt Options
	if len(opts) > 0 {
		opt = opts[0]
	}
	srv := &Server{
		auth:       auth,
		store:      st,
		catalog:    cat,
		download:   dl,
		broker:     broker,
		controller: controller,
		notifier:   notifier,
		corsOrigin: strings.TrimRight(strings.TrimSpace(opt.CORSOrigin), "/"),
		dataDir:    opt.DataDir,
		spaFS:      resolveSPAFilesystem(opt.StaticDir),
		mux:        http.NewServeMux(),
	}
	srv.routes()
	return srv
}

func (srv *Server) SetReady(ready bool) {
	srv.ready.Store(ready)
}

func (srv *Server) Handler() http.Handler {
	if srv.corsOrigin == "" {
		return srv.mux
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin == srv.corsOrigin {
			w.Header().Set("Access-Control-Allow-Origin", srv.corsOrigin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, PUT, DELETE, OPTIONS")
			w.Header().Set("Vary", "Origin")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		srv.mux.ServeHTTP(w, r)
	})
}

func (srv *Server) routes() {
	srv.mux.HandleFunc("GET /healthz", srv.handleHealthz)
	srv.mux.HandleFunc("GET /readyz", srv.handleReadyz)

	srv.mux.HandleFunc("GET /api/setup/status", srv.handleSetupStatus)
	srv.mux.HandleFunc("POST /api/setup", srv.handleSetup)
	srv.mux.HandleFunc("POST /api/auth/login", srv.handleLogin)
	srv.mux.HandleFunc("POST /api/auth/logout", srv.withAuthMutation(srv.handleLogout))
	srv.mux.HandleFunc("GET /api/auth/me", srv.withAuth(srv.handleMe))

	srv.mux.HandleFunc("GET /api/remote/folders", srv.withAuth(srv.handleRemoteFolders))
	srv.mux.HandleFunc("GET /api/sources", srv.withAuth(srv.handleListSources))
	srv.mux.HandleFunc("POST /api/sources", srv.withAuthMutation(srv.handleCreateSource))
	srv.mux.HandleFunc("PATCH /api/sources/{id}", srv.withAuthMutation(srv.handleUpdateSource))
	srv.mux.HandleFunc("DELETE /api/sources/{id}", srv.withAuthMutation(srv.handleDeleteSource))
	srv.mux.HandleFunc("POST /api/sources/scan", srv.withAuthMutation(srv.handleScanAllSources))
	srv.mux.HandleFunc("POST /api/sources/{id}/scan", srv.withAuthMutation(srv.handleScanSource))

	srv.mux.HandleFunc("GET /api/tasks", srv.withAuth(srv.handleListTasks))
	srv.mux.HandleFunc("POST /api/tasks/batch", srv.withAuthMutation(srv.handleBatchTasks))
	srv.mux.HandleFunc("POST /api/tasks/{id}/pause", srv.withAuthMutation(srv.handlePauseTask))
	srv.mux.HandleFunc("POST /api/tasks/{id}/resume", srv.withAuthMutation(srv.handleResumeTask))
	srv.mux.HandleFunc("POST /api/tasks/{id}/cancel", srv.withAuthMutation(srv.handleCancelTask))
	srv.mux.HandleFunc("POST /api/tasks/{id}/retry", srv.withAuthMutation(srv.handleRetryTask))

	srv.mux.HandleFunc("GET /api/files", srv.withAuth(srv.handleListFiles))
	srv.mux.HandleFunc("GET /api/system/stats", srv.withAuth(srv.handleSystemStats))
	srv.mux.HandleFunc("GET /api/system/settings", srv.withAuth(srv.handleGetSettings))
	srv.mux.HandleFunc("PUT /api/system/settings", srv.withAuthMutation(srv.handleUpdateSettings))
	srv.mux.HandleFunc("GET /api/system/local-dirs", srv.withAuth(srv.handleLocalDirs))
	srv.mux.HandleFunc("GET /api/system/account", srv.withAuth(srv.handleGetAccount))
	srv.mux.HandleFunc("PUT /api/system/account", srv.withAuthMutation(srv.handleUpdateAccount))
	srv.mux.HandleFunc("PUT /api/system/admin-password", srv.withAuthMutation(srv.handleAdminPassword))
	srv.mux.HandleFunc("GET /api/notifications", srv.withAuth(srv.handleListNotifications))
	srv.mux.HandleFunc("POST /api/notifications", srv.withAuthMutation(srv.handleCreateNotification))
	srv.mux.HandleFunc("PATCH /api/notifications/{id}", srv.withAuthMutation(srv.handleUpdateNotification))
	srv.mux.HandleFunc("DELETE /api/notifications/{id}", srv.withAuthMutation(srv.handleDeleteNotification))
	srv.mux.HandleFunc("POST /api/notifications/{id}/test", srv.withAuthMutation(srv.handleTestNotification))
	srv.mux.HandleFunc("GET /api/events", srv.withAuth(srv.handleEvents))

	srv.mux.HandleFunc("/", srv.handleFallback)
}

func (srv *Server) handleFallback(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/api/") {
		writeError(w, http.StatusNotFound, "not_found", "API endpoint not found")
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	srv.handleSPA(w, r)
}

func (srv *Server) handleSPA(w http.ResponseWriter, r *http.Request) {
	cleaned := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
	if cleaned != "" && cleaned != "." {
		if info, err := fs.Stat(srv.spaFS, cleaned); err == nil && !info.IsDir() {
			if strings.HasPrefix(cleaned, "assets/") {
				w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
			}
			http.FileServer(http.FS(srv.spaFS)).ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(cleaned, "assets/") {
			http.NotFound(w, r)
			return
		}
	}
	raw, err := fs.ReadFile(srv.spaFS, "index.html")
	if err != nil {
		http.Error(w, "ui unavailable", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(raw)
}

func (srv *Server) handleHealthz(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (srv *Server) handleReadyz(w http.ResponseWriter, r *http.Request) {
	if !srv.ready.Load() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "starting"})
		return
	}
	if err := srv.store.Ping(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "store_unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func (srv *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid login payload")
		return
	}
	needsSetup, err := srv.controller.NeedsSetup(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if needsSetup {
		writeError(w, http.StatusConflict, "setup_required", "请先完成首次配置")
		return
	}
	ok, err := srv.auth.VerifyCredentials(r.Context(), req.Username, req.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if !ok {
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
		return
	}
	sess, err := srv.auth.CreateSession(r, w, strings.TrimSpace(req.Username))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "session_error", "failed to create session")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"username":   sess.Username,
		"csrf_token": sess.CSRFToken,
	})
}

func (srv *Server) handleSetupStatus(w http.ResponseWriter, r *http.Request) {
	status, err := srv.controller.Status(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if !status.NeedsSetup {
		writeJSON(w, http.StatusOK, map[string]any{"needs_setup": false})
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (srv *Server) handleSetup(w http.ResponseWriter, r *http.Request) {
	var input control.SetupInput
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024)).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid setup payload")
		return
	}
	admin, err := srv.controller.Setup(r.Context(), input)
	if err != nil {
		switch {
		case errors.Is(err, control.ErrInvalidToken):
			writeError(w, http.StatusForbidden, "invalid_setup_token", "首次配置令牌无效")
		case errors.Is(err, control.ErrSetupAlreadyDone):
			writeError(w, http.StatusConflict, "setup_completed", "首次配置已经完成")
		default:
			writeError(w, http.StatusBadRequest, "setup_failed", err.Error())
		}
		return
	}
	sess, err := srv.auth.CreateSession(r, w, admin.Username)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "session_error", "首次配置成功，但创建登录会话失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"username":   admin.Username,
		"csrf_token": sess.CSRFToken,
	})
}

func (srv *Server) handleLogout(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	srv.auth.ClearSession(r, w)
	writeJSON(w, http.StatusOK, map[string]string{"status": "logged_out"})
}

func (srv *Server) handleMe(w http.ResponseWriter, _ *http.Request, sess store.AdminSession) {
	writeJSON(w, http.StatusOK, map[string]string{
		"username":   sess.Username,
		"csrf_token": sess.CSRFToken,
	})
}

func (srv *Server) handleRemoteFolders(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	parentID := r.URL.Query().Get("parent_id")
	cursor := r.URL.Query().Get("cursor")
	res, err := srv.catalog.BrowseRemoteFolder(r.Context(), parentID, cursor)
	if err != nil {
		writeBackendError(w, err)
		return
	}
	type item struct {
		ID      string `json:"id"`
		Name    string `json:"name"`
		IsDir   bool   `json:"is_dir"`
		Size    int64  `json:"size"`
		ModTime int64  `json:"mod_time"`
	}
	out := make([]item, 0, len(res.Entries))
	for _, entry := range res.Entries {
		out = append(out, item{
			ID:      entry.ID,
			Name:    entry.Name,
			IsDir:   entry.IsDir,
			Size:    entry.Size,
			ModTime: entry.ModTime.Unix(),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"entries":     out,
		"next_cursor": res.NextCursor,
	})
}

func (srv *Server) handleListSources(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	sources, err := srv.store.ListSources(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sources": sources})
}

func (srv *Server) handleCreateSource(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var input catalog.CreateSourceInput
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024)).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid source payload")
		return
	}
	src, err := srv.catalog.CreateSource(r.Context(), input)
	if err != nil {
		switch {
		case errors.Is(err, catalog.ErrInvalidSource):
			writeError(w, http.StatusBadRequest, "invalid_source", err.Error())
		case errors.Is(err, store.ErrDuplicateAlias):
			writeError(w, http.StatusConflict, "duplicate_alias", err.Error())
		default:
			writeBackendError(w, err)
		}
		return
	}
	writeJSON(w, http.StatusCreated, src)
}

func (srv *Server) handleUpdateSource(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	id := r.PathValue("id")
	var req struct {
		Enabled          *bool  `json:"enabled"`
		ScanIntervalSec  *int   `json:"scan_interval_sec"`
		MinFileSizeBytes *int64 `json:"min_file_size_bytes"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid patch payload")
		return
	}
	src, err := srv.catalog.UpdateSource(r.Context(), id, req.Enabled, req.ScanIntervalSec, req.MinFileSizeBytes)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "source not found")
			return
		}
		if errors.Is(err, catalog.ErrInvalidSource) {
			writeError(w, http.StatusBadRequest, "invalid_source", err.Error())
			return
		}
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, src)
}

func (srv *Server) handleScanSource(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	id := r.PathValue("id")
	src, err := srv.catalog.TriggerScan(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "source not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted": true,
		"source":   src,
	})
}

func (srv *Server) handleScanAllSources(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var req struct {
		IncludeDisabled bool `json:"include_disabled"`
	}
	if r.ContentLength > 0 {
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8*1024)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid_json", "invalid scan-all payload")
			return
		}
	}
	count, err := srv.catalog.TriggerScanAll(r.Context(), req.IncludeDisabled)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "scan_schedule_error", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted":  true,
		"scheduled": count,
	})
}

func (srv *Server) handleDeleteSource(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	id := r.PathValue("id")
	if err := srv.catalog.DeleteSource(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, store.ErrNotFound):
			writeError(w, http.StatusNotFound, "not_found", "source not found")
		case errors.Is(err, store.ErrConflict):
			writeError(w, http.StatusConflict, "source_busy", "请先暂停或等待该来源进行中的扫描与下载任务后再移除")
		default:
			writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		}
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": true, "id": id})
}

type systemSettingsPayload struct {
	DownloadDir                       string `json:"download_dir"`
	DataDir                           string `json:"data_dir"`
	PikPakUsername                    string `json:"pikpak_username"`
	PikPakConfigured                  bool   `json:"pikpak_configured"`
	MaxConcurrentDownloads            int    `json:"max_concurrent_downloads"`
	SegmentThreads                    int    `json:"segment_threads"`
	SegmentMinSizeMiB                 int64  `json:"segment_min_size_mib"`
	DefaultScanIntervalSec            int    `json:"default_scan_interval_sec"`
	MinFreeSpaceMiB                   int64  `json:"min_free_space_mib"`
	NotificationStatusIntervalMinutes int    `json:"notification_status_interval_minutes"`
}

func (srv *Server) currentSettingsPayload(ctx context.Context) systemSettingsPayload {
	dlOpts := srv.download.CurrentOptions()
	status, _ := srv.controller.Status(ctx)
	statusInterval := 30
	if settings, settingsErr := srv.store.GetSettings(ctx); settingsErr == nil {
		if raw := strings.TrimSpace(settings["notification_status_interval_minutes"]); raw != "" {
			if parsed, parseErr := strconv.Atoi(raw); parseErr == nil && parsed >= 0 {
				statusInterval = parsed
			}
		}
	}
	return systemSettingsPayload{
		DownloadDir:                       srv.download.DownloadDir(),
		DataDir:                           status.DataDir,
		PikPakUsername:                    status.PikPakUsername,
		PikPakConfigured:                  status.PikPakConfigured,
		MaxConcurrentDownloads:            dlOpts.MaxConcurrent,
		SegmentThreads:                    dlOpts.SegmentThreads,
		SegmentMinSizeMiB:                 dlOpts.SegmentMinBytes / (1024 * 1024),
		DefaultScanIntervalSec:            int(srv.catalog.DefaultInterval().Seconds()),
		MinFreeSpaceMiB:                   dlOpts.MinFreeSpaceBytes / (1024 * 1024),
		NotificationStatusIntervalMinutes: statusInterval,
	}
}

func (srv *Server) handleGetSettings(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	writeJSON(w, http.StatusOK, srv.currentSettingsPayload(r.Context()))
}

func (srv *Server) handleUpdateSettings(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var req struct {
		DownloadDir                       *string `json:"download_dir"`
		MaxConcurrentDownloads            *int    `json:"max_concurrent_downloads"`
		SegmentThreads                    *int    `json:"segment_threads"`
		SegmentMinSizeMiB                 *int64  `json:"segment_min_size_mib"`
		DefaultScanIntervalSec            *int    `json:"default_scan_interval_sec"`
		MinFreeSpaceMiB                   *int64  `json:"min_free_space_mib"`
		NotificationStatusIntervalMinutes *int    `json:"notification_status_interval_minutes"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid settings payload")
		return
	}

	curr := srv.currentSettingsPayload(r.Context())
	nextConcurrent := curr.MaxConcurrentDownloads
	if req.MaxConcurrentDownloads != nil {
		if *req.MaxConcurrentDownloads < 1 || *req.MaxConcurrentDownloads > 16 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "最大并发数必须在 1 到 16 之间")
			return
		}
		nextConcurrent = *req.MaxConcurrentDownloads
	}
	nextInterval := curr.DefaultScanIntervalSec
	if req.DefaultScanIntervalSec != nil {
		if *req.DefaultScanIntervalSec < 10 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "默认扫描周期不能小于 10 秒")
			return
		}
		nextInterval = *req.DefaultScanIntervalSec
	}
	nextMinFreeMiB := curr.MinFreeSpaceMiB
	if req.MinFreeSpaceMiB != nil {
		if *req.MinFreeSpaceMiB < 0 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "保留空间不能为负数")
			return
		}
		nextMinFreeMiB = *req.MinFreeSpaceMiB
	}
	nextSegmentThreads := curr.SegmentThreads
	if req.SegmentThreads != nil {
		if *req.SegmentThreads < 1 || *req.SegmentThreads > 16 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "单文件分片连接数必须在 1 到 16 之间")
			return
		}
		nextSegmentThreads = *req.SegmentThreads
	}
	nextSegmentMinMiB := curr.SegmentMinSizeMiB
	if req.SegmentMinSizeMiB != nil {
		if *req.SegmentMinSizeMiB < 1 || *req.SegmentMinSizeMiB > 4096 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "最小分片大小必须在 1 到 4096 MiB 之间")
			return
		}
		nextSegmentMinMiB = *req.SegmentMinSizeMiB
	}

	nextDownloadDir := curr.DownloadDir
	if req.DownloadDir != nil {
		trimmed := strings.TrimSpace(*req.DownloadDir)
		if trimmed == "" {
			writeError(w, http.StatusBadRequest, "invalid_setting", "下载目录不能为空")
			return
		}
		absTarget, err := filepath.Abs(trimmed)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid_setting", err.Error())
			return
		}
		if srv.dataDir != "" && absTarget == srv.dataDir {
			writeError(w, http.StatusBadRequest, "invalid_setting", "下载目录不能与数据目录相同")
			return
		}
		if absTarget != curr.DownloadDir {
			switched, switchErr := srv.download.SwitchDownloadDir(r.Context(), absTarget)
			if switchErr != nil {
				if errors.Is(switchErr, store.ErrConflict) {
					writeError(w, http.StatusConflict, "downloads_active", "请先暂停或等待当前活动下载任务完成后再修改下载目录")
					return
				}
				writeError(w, http.StatusBadRequest, "download_dir_invalid", switchErr.Error())
				return
			}
			nextDownloadDir = switched
		}
	}

	nextMinFreeBytes := nextMinFreeMiB * 1024 * 1024
	nextStatusInterval := curr.NotificationStatusIntervalMinutes
	if req.NotificationStatusIntervalMinutes != nil {
		if *req.NotificationStatusIntervalMinutes < 0 || *req.NotificationStatusIntervalMinutes > 1440 {
			writeError(w, http.StatusBadRequest, "invalid_setting", "状态汇报间隔必须在 0 到 1440 分钟之间")
			return
		}
		nextStatusInterval = *req.NotificationStatusIntervalMinutes
	}
	srv.download.UpdateOptions(nextConcurrent, nextMinFreeBytes, nextSegmentThreads, nextSegmentMinMiB*1024*1024)
	srv.catalog.SetDefaultInterval(time.Duration(nextInterval) * time.Second)

	if err := srv.store.SaveSettings(r.Context(), map[string]string{
		"download_dir":                         nextDownloadDir,
		"max_concurrent_downloads":             strconv.Itoa(nextConcurrent),
		"segment_threads":                      strconv.Itoa(nextSegmentThreads),
		"segment_min_bytes":                    strconv.FormatInt(nextSegmentMinMiB*1024*1024, 10),
		"default_scan_interval_sec":            strconv.Itoa(nextInterval),
		"min_free_space_bytes":                 strconv.FormatInt(nextMinFreeBytes, 10),
		"notification_status_interval_minutes": strconv.Itoa(nextStatusInterval),
	}); err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}

	srv.broker.Publish(events.Event{Type: "settings.updated"})
	srv.broker.Publish(events.Event{Type: "system.stats"})
	writeJSON(w, http.StatusOK, srv.currentSettingsPayload(r.Context()))
}

func (srv *Server) handleGetAccount(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	status, err := srv.controller.Status(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pikpak_username":   status.PikPakUsername,
		"pikpak_configured": status.PikPakConfigured,
	})
}

func (srv *Server) handleUpdateAccount(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid account payload")
		return
	}
	if err := srv.controller.ConfigurePikPak(r.Context(), req.Username, req.Password); err != nil {
		switch {
		case errors.Is(err, control.ErrActiveTasks):
			writeError(w, http.StatusConflict, "downloads_active", "请先暂停或等待当前下载任务完成后再修改 PikPak 账号")
		case errors.Is(err, control.ErrAccountChange):
			writeError(w, http.StatusConflict, "account_change_blocked", "更换 PikPak 账号前请先移除已有监控目录")
		default:
			writeError(w, http.StatusBadRequest, "account_update_failed", err.Error())
		}
		return
	}
	srv.broker.Publish(events.Event{Type: "settings.updated"})
	writeJSON(w, http.StatusOK, map[string]any{
		"pikpak_username":   strings.TrimSpace(req.Username),
		"pikpak_configured": true,
	})
}

func (srv *Server) handleAdminPassword(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var req struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid password payload")
		return
	}
	if err := srv.controller.ChangeAdminPassword(r.Context(), req.CurrentPassword, req.NewPassword); err != nil {
		writeError(w, http.StatusBadRequest, "password_change_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"updated": true})
}

func (srv *Server) handleListNotifications(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	channels, err := srv.notifier.List(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"channels": channels})
}

func (srv *Server) handleCreateNotification(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var input notify.Input
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024)).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid notification payload")
		return
	}
	channel, err := srv.notifier.Create(r.Context(), input)
	if err != nil {
		writeError(w, http.StatusBadRequest, "notification_invalid", err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, channel)
}

func (srv *Server) handleUpdateNotification(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var input notify.Input
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32*1024)).Decode(&input); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid notification payload")
		return
	}
	channel, err := srv.notifier.Update(r.Context(), r.PathValue("id"), input)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "notification channel not found")
			return
		}
		writeError(w, http.StatusBadRequest, "notification_invalid", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, channel)
}

func (srv *Server) handleDeleteNotification(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	if err := srv.notifier.Delete(r.Context(), r.PathValue("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "notification channel not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "notification_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"deleted": true})
}

func (srv *Server) handleTestNotification(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	if err := srv.notifier.Test(r.Context(), r.PathValue("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "notification channel not found")
			return
		}
		writeError(w, http.StatusBadGateway, "notification_failed", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sent": true})
}

func (srv *Server) handleLocalDirs(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	reqPath := strings.TrimSpace(r.URL.Query().Get("path"))
	if reqPath == "" {
		reqPath = srv.download.DownloadDir()
	}
	current, parent, dirs, err := localfs.ListLocalSubdirs(reqPath)
	if err != nil {
		writeError(w, http.StatusBadRequest, "local_dir_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"current_path": current,
		"parent_path":  parent,
		"dirs":         dirs,
	})
}

func (srv *Server) handleListTasks(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	q := r.URL.Query()
	limit, offset := parsePagination(q.Get("limit"), q.Get("offset"))
	search := strings.ToLower(strings.TrimSpace(q.Get("q")))
	fetchLimit := limit
	fetchOffset := offset
	if search != "" {
		fetchLimit = 500
		fetchOffset = 0
	}
	tasks, err := srv.download.ListTaskViews(r.Context(), q.Get("state"), q.Get("source_id"), fetchLimit, fetchOffset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if search != "" {
		filtered := tasks[:0]
		for _, task := range tasks {
			if strings.Contains(strings.ToLower(task.TargetRelPath), search) ||
				strings.Contains(strings.ToLower(task.RemotePath), search) {
				filtered = append(filtered, task)
			}
		}
		tasks = filtered
		total := len(tasks)
		end := offset + limit
		if offset > total {
			tasks = nil
		} else {
			if end > total {
				end = total
			}
			tasks = tasks[offset:end]
		}
		writeJSON(w, http.StatusOK, map[string]any{"tasks": tasks, "total": total})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tasks": tasks, "total": len(tasks)})
}

func (srv *Server) handleBatchTasks(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	var req struct {
		Action   string `json:"action"`
		SourceID string `json:"source_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16*1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_json", "invalid batch payload")
		return
	}
	affected, err := srv.download.BatchAction(r.Context(), strings.TrimSpace(req.Action), strings.TrimSpace(req.SourceID))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_action", err.Error())
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted": true,
		"affected": affected,
	})
}

func (srv *Server) handlePauseTask(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	srv.handleTaskMutation(w, r, srv.download.PauseTask)
}

func (srv *Server) handleResumeTask(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	srv.handleTaskMutation(w, r, srv.download.ResumeTask)
}

func (srv *Server) handleCancelTask(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	srv.handleTaskMutation(w, r, srv.download.CancelTask)
}

func (srv *Server) handleRetryTask(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	srv.handleTaskMutation(w, r, srv.download.RetryTask)
}

func (srv *Server) handleTaskMutation(w http.ResponseWriter, r *http.Request, fn func(context.Context, string) (store.DownloadTask, error)) {
	id := r.PathValue("id")
	task, err := fn(r.Context(), id)
	if err != nil {
		switch {
		case errors.Is(err, store.ErrNotFound):
			writeError(w, http.StatusNotFound, "not_found", "task not found")
		case errors.Is(err, store.ErrConflict):
			writeError(w, http.StatusConflict, "invalid_transition", "operation not allowed in current task state")
		default:
			writeError(w, http.StatusInternalServerError, "task_error", err.Error())
		}
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]any{
		"accepted": true,
		"task":     task,
	})
}

func (srv *Server) handleListFiles(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	q := r.URL.Query()
	limit, offset := parsePagination(q.Get("limit"), q.Get("offset"))
	search := strings.ToLower(strings.TrimSpace(q.Get("q")))
	fetchLimit := limit
	fetchOffset := offset
	if search != "" {
		fetchLimit = 500
		fetchOffset = 0
	}
	files, err := srv.store.ListFileViews(r.Context(), q.Get("source_id"), q.Get("remote_state"), fetchLimit, fetchOffset)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "store_error", err.Error())
		return
	}
	if search != "" {
		filtered := files[:0]
		for _, file := range files {
			localPath := ""
			if file.Delivery != nil {
				localPath = file.Delivery.LocalRelPath
			}
			if strings.Contains(strings.ToLower(file.RemoteObject.RelativePath), search) ||
				strings.Contains(strings.ToLower(localPath), search) {
				filtered = append(filtered, file)
			}
		}
		files = filtered
		total := len(files)
		if offset > total {
			files = nil
		} else {
			end := offset + limit
			if end > total {
				end = total
			}
			files = files[offset:end]
		}
		writeJSON(w, http.StatusOK, map[string]any{"files": files, "total": total})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"files": files, "total": len(files)})
}

func (srv *Server) handleSystemStats(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	stats, err := srv.download.Stats(r.Context())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "stats_error", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, stats)
}

func (srv *Server) handleEvents(w http.ResponseWriter, r *http.Request, _ store.AdminSession) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	ch, unsubscribe := srv.broker.Subscribe(64)
	defer unsubscribe()

	fmt.Fprintf(w, "event: ready\ndata: {\"timestamp\":%d}\n\n", time.Now().Unix())
	flusher.Flush()

	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case ev, open := <-ch:
			if !open {
				return
			}
			payload, _ := json.Marshal(ev)
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Type, payload)
			flusher.Flush()
		case <-heartbeat.C:
			fmt.Fprintf(w, "event: system.stats\ndata: {\"timestamp\":%d}\n\n", time.Now().Unix())
			flusher.Flush()
		}
	}
}

type authHandler func(http.ResponseWriter, *http.Request, store.AdminSession)

func (srv *Server) withAuth(next authHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sess, ok := srv.auth.Authenticate(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		next(w, r, sess)
	}
}

func (srv *Server) withAuthMutation(next authHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		sess, ok := srv.auth.Authenticate(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		if !srv.auth.CheckOrigin(r, srv.corsOrigin) {
			writeError(w, http.StatusForbidden, "origin_invalid", "cross-origin request rejected")
			return
		}
		if !srv.auth.CheckCSRF(r, sess) {
			writeError(w, http.StatusForbidden, "csrf_invalid", "invalid or missing CSRF token")
			return
		}
		next(w, r, sess)
	}
}

func parsePagination(rawLimit, rawOffset string) (int, int) {
	limit := 100
	if val, err := strconv.Atoi(strings.TrimSpace(rawLimit)); err == nil && val > 0 && val <= 500 {
		limit = val
	}
	offset := 0
	if val, err := strconv.Atoi(strings.TrimSpace(rawOffset)); err == nil && val >= 0 {
		offset = val
	}
	return limit, offset
}

func writeBackendError(w http.ResponseWriter, err error) {
	kind := backend.KindOf(err)
	switch kind {
	case backend.ErrNotFound:
		writeError(w, http.StatusNotFound, string(kind), err.Error())
	case backend.ErrAuth, backend.ErrCaptcha:
		writeError(w, http.StatusBadGateway, string(kind), err.Error())
	case backend.ErrRateLimited:
		writeError(w, http.StatusTooManyRequests, string(kind), err.Error())
	default:
		writeError(w, http.StatusBadGateway, "backend_error", err.Error())
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{
		"error":   code,
		"message": message,
	})
}
