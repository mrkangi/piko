package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/config"
	"github.com/nihildigit/piko/download-server/internal/control"
	"github.com/nihildigit/piko/download-server/internal/download"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/logview"
	"github.com/nihildigit/piko/download-server/internal/netdns"
	"github.com/nihildigit/piko/download-server/internal/notify"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
	"github.com/nihildigit/piko/download-server/internal/updater"
	"github.com/nihildigit/piko/download-server/internal/web"
)

var (
	version    = "dev"
	buildTime  = ""
	updateRepo = "NihilDigit/piko"
)

func main() {
	if len(os.Args) > 1 && (os.Args[1] == "--version" || os.Args[1] == "-version") {
		if buildTime == "" {
			fmt.Printf("download-server %s\n", version)
		} else {
			fmt.Printf("download-server %s (%s)\n", version, buildTime)
		}
		return
	}
	logview.InstallDefault()
	cfg, err := config.LoadFromEnv()
	if err != nil {
		log.Fatalf("configuration error: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), notifySignals()...)
	defer stop()

	if err := runServer(ctx, cfg, nil, nil); err != nil {
		log.Fatalf("server exited with error: %v", err)
	}
}

func runServer(ctx context.Context, cfg config.Config, catOverride backend.Catalog, contentOverride backend.ContentSource) error {
	logs := logview.InstallDefault()
	netdns.Configure()

	lock, err := config.AcquireInstanceLock(cfg.DataDir)
	if err != nil {
		return err
	}
	defer lock.Close()

	st, err := store.Open(cfg.DataDir)
	if err != nil {
		return err
	}
	defer st.Close()

	secrets, err := secret.LoadOrCreate(cfg.DataDir)
	if err != nil {
		return err
	}

	maxConcurrent := config.DefaultMaxConcurrent
	segmentThreads := config.DefaultSegmentThreads
	segmentMinBytes := int64(config.DefaultSegmentMinBytes)
	scanInterval := config.DefaultScanInterval
	scanFailureThreshold := config.DefaultScanFailureThreshold
	minFreeSpace := int64(config.DefaultMinFreeSpaceBytes)
	defaultDownloadDir := cfg.DownloadDir
	if saved, err := st.GetSettings(ctx); err == nil {
		if dir := saved["download_dir"]; dir != "" && dir != cfg.DataDir {
			cfg.DownloadDir = dir
		}
		if v, err := strconv.Atoi(saved["max_concurrent_downloads"]); err == nil && v >= 1 && v <= 16 {
			maxConcurrent = v
		}
		if v, err := strconv.Atoi(saved["segment_threads"]); err == nil && v >= 1 && v <= 16 {
			segmentThreads = v
		}
		if v, err := strconv.ParseInt(saved["segment_min_bytes"], 10, 64); err == nil && v >= 1024*1024 {
			segmentMinBytes = v
		}
		if v, err := strconv.Atoi(saved["default_scan_interval_sec"]); err == nil && v >= 10 {
			scanInterval = time.Duration(v) * time.Second
		}
		if v, err := strconv.Atoi(saved["scan_failure_threshold"]); err == nil && v >= 1 && v <= 20 {
			scanFailureThreshold = v
		}
		if v, err := strconv.ParseInt(saved["min_free_space_bytes"], 10, 64); err == nil && v >= 0 {
			minFreeSpace = v
		}
	}

	fsStore, err := openDownloadStore(ctx, &cfg, st, defaultDownloadDir)
	if err != nil {
		return err
	}
	defer fsStore.Close()

	runtime := backend.NewRuntime()
	if catOverride != nil && contentOverride != nil {
		runtime.Configure(catOverride, contentOverride)
	}

	broker := events.NewBroker()
	catSvc := catalog.NewService(st, runtime, broker, "", scanInterval, config.DefaultMaxRetries)
	catSvc.SetFailureThreshold(scanFailureThreshold)
	dlSvc := download.NewService(st, fsStore, runtime, runtime, broker, download.Options{
		MaxConcurrent:     maxConcurrent,
		MinFreeSpaceBytes: minFreeSpace,
		SegmentThreads:    segmentThreads,
		SegmentMinBytes:   segmentMinBytes,
		NoProgressTimeout: config.DefaultNoProgressTimeout,
		MaxRetries:        config.DefaultMaxRetries,
	})
	catSvc.SetOnQueued(dlSvc.Signal)

	controller := control.New(st, secrets, runtime, catSvc, dlSvc, broker, cfg.DataDir)
	notifier := notify.NewManager(st, secrets, broker, func(ctx context.Context) (notify.StatusSnapshot, error) {
		report, err := dlSvc.StatusReport(ctx)
		if err != nil {
			return notify.StatusSnapshot{}, err
		}
		return notify.StatusSnapshot{
			Queued:           report.Queued,
			Active:           report.Active,
			Paused:           report.Paused,
			RemainingTasks:   report.RemainingTasks,
			RemainingBytes:   report.RemainingBytes,
			SpeedBytesPerSec: report.SpeedBytesPerSec,
		}, nil
	})
	if catOverride == nil {
		if err := controller.LoadRuntime(ctx); err != nil {
			return err
		}
	}

	if err := dlSvc.StartupRecover(ctx); err != nil {
		return fmt.Errorf("startup recovery failed: %w", err)
	}

	if needsSetup, setupErr := controller.NeedsSetup(ctx); setupErr != nil {
		return setupErr
	} else if needsSetup {
		token, tokenErr := controller.EnsureSetupToken(ctx)
		if tokenErr != nil {
			return tokenErr
		}
		log.Printf("首次启动：请打开 http://<本机地址>%s/setup 完成配置", portSuffix(cfg.ListenAddr))
		log.Printf("Setup Token: %s", token)
	}

	auth := web.NewAuthenticator(st, false, false)
	updateSvc := updater.New(version, buildTime, updateRepo)
	webSrv := web.NewServer(auth, st, catSvc, dlSvc, broker, controller, notifier, web.Options{
		DataDir: cfg.DataDir,
		Logs:    logs,
		Updater: updateSvc,
	})
	webSrv.SetReady(true)

	httpServer := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           webSrv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
	}

	bgCtx, bgCancel := context.WithCancel(ctx)
	defer bgCancel()
	var wg sync.WaitGroup
	wg.Add(2)
	wg.Add(1)
	go func() {
		defer wg.Done()
		notifier.Run(bgCtx)
	}()
	go func() {
		defer wg.Done()
		catSvc.RunLoop(bgCtx)
	}()
	go func() {
		defer wg.Done()
		dlSvc.RunLoop(bgCtx)
	}()

	errCh := make(chan error, 1)
	go func() {
		log.Printf("download-server %s listening on %s", version, cfg.ListenAddr)
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	select {
	case <-ctx.Done():
		webSrv.SetReady(false)
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		_ = httpServer.Shutdown(shutdownCtx)
		cancel()
		bgCancel()
		wg.Wait()
		return nil
	case serveErr := <-errCh:
		webSrv.SetReady(false)
		bgCancel()
		wg.Wait()
		return serveErr
	}
}

func portSuffix(addr string) string {
	for idx := len(addr) - 1; idx >= 0; idx-- {
		if addr[idx] == ':' {
			return addr[idx:]
		}
	}
	return ":" + strconv.Itoa(config.DefaultPort)
}

// openDownloadStore 打开并探测下载目录。已保存的目录位于不支持硬链接的
// 文件系统（典型为 Android 共享存储）时回退到默认目录，避免服务因一份
// 历史设置而无法启动、连管理页都进不去。
func openDownloadStore(ctx context.Context, cfg *config.Config, st *store.Store, defaultDir string) (*localfs.Store, error) {
	candidates := []string{cfg.DownloadDir}
	if defaultDir != "" && defaultDir != cfg.DownloadDir {
		candidates = append(candidates, defaultDir)
	}

	var lastErr error
	for _, dir := range candidates {
		fsStore, err := localfs.Open(dir)
		if err != nil {
			lastErr = fmt.Errorf("open download directory %s: %w", dir, err)
			continue
		}
		if err := fsStore.Probe(); err != nil {
			_ = fsStore.Close()
			lastErr = fmt.Errorf("download volume probe failed for %s: %w", dir, err)
			continue
		}
		if dir != cfg.DownloadDir {
			log.Printf("download directory %s is unusable, falling back to %s: %v", cfg.DownloadDir, dir, lastErr)
			cfg.DownloadDir = dir
			if err := st.SaveSettings(ctx, map[string]string{"download_dir": dir}); err != nil {
				log.Printf("persist fallback download directory: %v", err)
			}
		}
		return fsStore, nil
	}
	if lastErr == nil {
		lastErr = errors.New("no usable download directory")
	}
	return nil, lastErr
}
