package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
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
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
	"github.com/nihildigit/piko/download-server/internal/web"
)

func main() {
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
	scanInterval := config.DefaultScanInterval
	minFreeSpace := int64(config.DefaultMinFreeSpaceBytes)
	if saved, err := st.GetSettings(ctx); err == nil {
		if dir := saved["download_dir"]; dir != "" && dir != cfg.DataDir {
			cfg.DownloadDir = dir
		}
		if v, err := strconv.Atoi(saved["max_concurrent_downloads"]); err == nil && v >= 1 && v <= 16 {
			maxConcurrent = v
		}
		if v, err := strconv.Atoi(saved["default_scan_interval_sec"]); err == nil && v >= 10 {
			scanInterval = time.Duration(v) * time.Second
		}
		if v, err := strconv.ParseInt(saved["min_free_space_bytes"], 10, 64); err == nil && v >= 0 {
			minFreeSpace = v
		}
	}

	fsStore, err := localfs.Open(cfg.DownloadDir)
	if err != nil {
		return err
	}
	defer fsStore.Close()
	if err := fsStore.Probe(); err != nil {
		return fmt.Errorf("download volume probe failed: %w", err)
	}

	runtime := backend.NewRuntime()
	if catOverride != nil && contentOverride != nil {
		runtime.Configure(catOverride, contentOverride)
	}

	broker := events.NewBroker()
	catSvc := catalog.NewService(st, runtime, broker, "", scanInterval, config.DefaultMaxRetries)
	dlSvc := download.NewService(st, fsStore, runtime, runtime, broker, download.Options{
		MaxConcurrent:     maxConcurrent,
		MinFreeSpaceBytes: minFreeSpace,
		NoProgressTimeout: config.DefaultNoProgressTimeout,
		MaxRetries:        config.DefaultMaxRetries,
	})
	catSvc.SetOnQueued(dlSvc.Signal)

	controller := control.New(st, secrets, runtime, catSvc, dlSvc, broker, cfg.DataDir)
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
	webSrv := web.NewServer(auth, st, catSvc, dlSvc, broker, controller, web.Options{
		DataDir: cfg.DataDir,
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
		log.Printf("download-server listening on %s", cfg.ListenAddr)
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
