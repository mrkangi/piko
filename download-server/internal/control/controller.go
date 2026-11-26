package control

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/config"
	"github.com/nihildigit/piko/download-server/internal/download"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/netproxy"
	"github.com/nihildigit/piko/download-server/internal/pikpak"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

var (
	ErrSetupIncomplete  = errors.New("setup is not complete")
	ErrSetupAlreadyDone = errors.New("setup is already complete")
	ErrInvalidToken     = errors.New("invalid setup token")
	ErrActiveTasks      = errors.New("active download tasks must be stopped first")
	ErrAccountChange    = errors.New("remove existing sources before changing PikPak account")
)

type SetupInput struct {
	Token          string `json:"token"`
	AdminUsername  string `json:"admin_username"`
	AdminPassword  string `json:"admin_password"`
	PikPakUsername string `json:"pikpak_username"`
	PikPakPassword string `json:"pikpak_password"`
	DownloadDir    string `json:"download_dir"`
}

type RemoteClient interface {
	backend.Catalog
	backend.ContentSource
	Verify(context.Context) error
	Probe(context.Context) error
}

type ClientFactory func(username string, password string, proxyURL string) (RemoteClient, string, error)

type ProxyStatus struct {
	Configured bool
	MaskedURL  string
}

type Status struct {
	NeedsSetup       bool   `json:"needs_setup"`
	AdminUsername    string `json:"admin_username,omitempty"`
	PikPakUsername   string `json:"pikpak_username,omitempty"`
	PikPakConfigured bool   `json:"pikpak_configured"`
	DownloadDir      string `json:"download_dir"`
	DataDir          string `json:"data_dir"`
}

type Controller struct {
	store     *store.Store
	secrets   *secret.Cipher
	runtime   *backend.Runtime
	catalog   *catalog.Service
	download  *download.Service
	broker    *events.Broker
	dataDir   string
	newClient ClientFactory

	setupMu    sync.Mutex
	setupToken string
}

func New(
	st *store.Store,
	secrets *secret.Cipher,
	runtime *backend.Runtime,
	cat *catalog.Service,
	dl *download.Service,
	broker *events.Broker,
	dataDir string,
) *Controller {
	controller := &Controller{
		store:    st,
		secrets:  secrets,
		runtime:  runtime,
		catalog:  cat,
		download: dl,
		broker:   broker,
		dataDir:  dataDir,
	}
	controller.newClient = controller.buildRealClient
	return controller
}

func (c *Controller) SetClientFactory(factory ClientFactory) {
	if factory != nil {
		c.newClient = factory
	}
}

func (c *Controller) EnsureSetupToken(ctx context.Context) (string, error) {
	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return "", err
	}
	if hasAdmin {
		return "", nil
	}
	c.setupMu.Lock()
	defer c.setupMu.Unlock()
	if c.setupToken == "" {
		var raw [32]byte
		if _, err := rand.Read(raw[:]); err != nil {
			return "", err
		}
		c.setupToken = hex.EncodeToString(raw[:])
	}
	return c.setupToken, nil
}

func (c *Controller) NeedsSetup(ctx context.Context) (bool, error) {
	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return false, err
	}
	return !hasAdmin, nil
}

func (c *Controller) Status(ctx context.Context) (Status, error) {
	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return Status{}, err
	}
	status := Status{
		NeedsSetup:  !hasAdmin,
		DownloadDir: c.download.DownloadDir(),
		DataDir:     c.dataDir,
	}
	if !hasAdmin {
		return status, nil
	}
	admin, err := c.store.GetAdmin(ctx)
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		return Status{}, err
	}
	status.AdminUsername = admin.Username
	settings, err := c.store.GetSettings(ctx)
	if err != nil {
		return Status{}, err
	}
	status.PikPakUsername = strings.TrimSpace(settings["pikpak_username"])
	status.PikPakConfigured = status.PikPakUsername != "" && strings.TrimSpace(settings["pikpak_password_cipher"]) != ""
	return status, nil
}

func (c *Controller) Setup(ctx context.Context, input SetupInput) (store.AdminUser, error) {
	c.setupMu.Lock()
	defer c.setupMu.Unlock()

	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return store.AdminUser{}, err
	}
	if hasAdmin {
		return store.AdminUser{}, ErrSetupAlreadyDone
	}
	if c.setupToken == "" || subtle.ConstantTimeCompare([]byte(strings.TrimSpace(input.Token)), []byte(c.setupToken)) != 1 {
		return store.AdminUser{}, ErrInvalidToken
	}

	adminUsername := strings.TrimSpace(input.AdminUsername)
	if adminUsername == "" || len(adminUsername) > 64 {
		return store.AdminUser{}, fmt.Errorf("管理员用户名不能为空且不能超过 64 个字符")
	}
	if len(input.AdminPassword) < 8 {
		return store.AdminUser{}, fmt.Errorf("管理员密码至少需要 8 个字符")
	}
	pikpakUsername := strings.TrimSpace(input.PikPakUsername)
	if pikpakUsername == "" || strings.TrimSpace(input.PikPakPassword) == "" {
		return store.AdminUser{}, fmt.Errorf("PikPak 账号和密码不能为空")
	}

	client, accountID, err := c.newClient(pikpakUsername, input.PikPakPassword, "")
	if err != nil {
		return store.AdminUser{}, err
	}
	if err := client.Verify(ctx); err != nil {
		return store.AdminUser{}, fmt.Errorf("PikPak 登录失败: %w", err)
	}

	downloadDir := strings.TrimSpace(input.DownloadDir)
	if downloadDir == "" {
		downloadDir = c.download.DownloadDir()
	}
	switchedDir, err := c.download.SwitchDownloadDir(ctx, downloadDir)
	if err != nil {
		return store.AdminUser{}, fmt.Errorf("下载目录不可用: %w", err)
	}
	encryptedPassword, err := c.secrets.Encrypt(input.PikPakPassword)
	if err != nil {
		return store.AdminUser{}, err
	}
	adminHash, err := secret.HashPassword(input.AdminPassword)
	if err != nil {
		return store.AdminUser{}, err
	}
	if err := c.store.SaveSettings(ctx, map[string]string{
		"pikpak_username":           pikpakUsername,
		"pikpak_password_cipher":    encryptedPassword,
		"account_id":                accountID,
		"download_dir":              switchedDir,
		"max_concurrent_downloads":  fmt.Sprintf("%d", config.DefaultMaxConcurrent),
		"segment_threads":           fmt.Sprintf("%d", config.DefaultSegmentThreads),
		"segment_min_bytes":         fmt.Sprintf("%d", config.DefaultSegmentMinBytes),
		"default_scan_interval_sec": fmt.Sprintf("%d", int(config.DefaultScanInterval.Seconds())),
		"scan_failure_threshold":    fmt.Sprintf("%d", config.DefaultScanFailureThreshold),
		"min_free_space_bytes":      fmt.Sprintf("%d", config.DefaultMinFreeSpaceBytes),
	}); err != nil {
		return store.AdminUser{}, err
	}
	admin, err := c.store.CreateAdmin(ctx, adminUsername, adminHash)
	if err != nil {
		return store.AdminUser{}, err
	}
	c.runtime.Configure(client, client)
	c.catalog.SetAccountID(accountID)
	c.setupToken = ""
	c.broker.Publish(events.Event{Type: "setup.completed"})
	c.broker.Publish(events.Event{Type: "settings.updated"})
	return admin, nil
}

func (c *Controller) LoadRuntime(ctx context.Context) error {
	settings, err := c.store.GetSettings(ctx)
	if err != nil {
		return err
	}
	username := strings.TrimSpace(settings["pikpak_username"])
	encrypted := strings.TrimSpace(settings["pikpak_password_cipher"])
	if username == "" || encrypted == "" {
		return nil
	}
	password, err := c.secrets.Decrypt(encrypted)
	if err != nil {
		return fmt.Errorf("decrypt PikPak credentials: %w", err)
	}
	proxyURL, err := c.currentProxyURL(ctx, settings)
	if err != nil {
		return err
	}
	client, accountID, err := c.newClient(username, password, proxyURL)
	if err != nil {
		return err
	}
	c.runtime.Configure(client, client)
	c.catalog.SetAccountID(accountID)
	return nil
}

func (c *Controller) ConfigurePikPak(ctx context.Context, username string, password string) error {
	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return err
	}
	if !hasAdmin {
		return ErrSetupIncomplete
	}
	username = strings.TrimSpace(username)
	if username == "" || strings.TrimSpace(password) == "" {
		return fmt.Errorf("PikPak 账号和密码不能为空")
	}
	hasActive, err := c.store.HasActiveTasks(ctx)
	if err != nil {
		return err
	}
	if hasActive {
		return ErrActiveTasks
	}
	settings, err := c.store.GetSettings(ctx)
	if err != nil {
		return err
	}
	proxyURL, err := c.currentProxyURL(ctx, settings)
	if err != nil {
		return err
	}
	oldUsername := strings.TrimSpace(settings["pikpak_username"])
	if oldUsername != "" && oldUsername != username {
		sources, listErr := c.store.ListSources(ctx)
		if listErr != nil {
			return listErr
		}
		if len(sources) > 0 {
			return ErrAccountChange
		}
	}
	client, accountID, err := c.newClient(username, password, proxyURL)
	if err != nil {
		return err
	}
	if err := client.Verify(ctx); err != nil {
		return fmt.Errorf("PikPak 登录失败: %w", err)
	}
	encrypted, err := c.secrets.Encrypt(password)
	if err != nil {
		return err
	}
	if err := c.store.SaveSettings(ctx, map[string]string{
		"pikpak_username":        username,
		"pikpak_password_cipher": encrypted,
		"account_id":             accountID,
	}); err != nil {
		return err
	}
	c.runtime.Configure(client, client)
	c.catalog.SetAccountID(accountID)
	c.broker.Publish(events.Event{Type: "settings.updated"})
	return nil
}

func (c *Controller) ProxyStatus(ctx context.Context) (ProxyStatus, error) {
	settings, err := c.store.GetSettings(ctx)
	if err != nil {
		return ProxyStatus{}, err
	}
	encrypted := strings.TrimSpace(settings["proxy_url_cipher"])
	if encrypted == "" {
		return ProxyStatus{}, nil
	}
	plain, err := c.secrets.Decrypt(encrypted)
	if err != nil {
		return ProxyStatus{}, fmt.Errorf("decrypt proxy URL: %w", err)
	}
	masked, err := netproxy.Mask(plain)
	if err != nil {
		return ProxyStatus{}, err
	}
	return ProxyStatus{Configured: true, MaskedURL: masked}, nil
}

func (c *Controller) ConfigureProxy(ctx context.Context, rawURL string, clear bool) error {
	hasAdmin, err := c.store.HasAdmin(ctx)
	if err != nil {
		return err
	}
	if !hasAdmin {
		return ErrSetupIncomplete
	}
	if clear && strings.TrimSpace(rawURL) != "" {
		return fmt.Errorf("不能同时设置和清除代理")
	}
	proxyURL := ""
	if !clear {
		proxyURL, err = netproxy.Normalize(rawURL)
		if err != nil {
			return err
		}
	}

	settings, err := c.store.GetSettings(ctx)
	if err != nil {
		return err
	}
	username := strings.TrimSpace(settings["pikpak_username"])
	encryptedPassword := strings.TrimSpace(settings["pikpak_password_cipher"])
	var client RemoteClient
	var accountID string
	if username != "" && encryptedPassword != "" {
		password, decryptErr := c.secrets.Decrypt(encryptedPassword)
		if decryptErr != nil {
			return fmt.Errorf("decrypt PikPak credentials: %w", decryptErr)
		}
		client, accountID, err = c.newClient(username, password, proxyURL)
		if err != nil {
			return err
		}
		if proxyURL != "" {
			if err := client.Probe(ctx); err != nil {
				return fmt.Errorf("代理连接验证失败: %w", err)
			}
		}
	}

	encryptedProxy := ""
	if proxyURL != "" {
		encryptedProxy, err = c.secrets.Encrypt(proxyURL)
		if err != nil {
			return err
		}
	}
	if err := c.store.SaveSettings(ctx, map[string]string{"proxy_url_cipher": encryptedProxy}); err != nil {
		return err
	}
	if client != nil {
		c.runtime.Configure(client, client)
		c.catalog.SetAccountID(accountID)
	}
	c.broker.Publish(events.Event{Type: "settings.updated"})
	return nil
}

func (c *Controller) ChangeAdminPassword(ctx context.Context, currentPassword string, newPassword string) error {
	admin, err := c.store.GetAdmin(ctx)
	if err != nil {
		return err
	}
	if !secret.CheckPassword(admin.PasswordHash, currentPassword) {
		return fmt.Errorf("当前管理员密码不正确")
	}
	if len(newPassword) < 8 {
		return fmt.Errorf("新密码至少需要 8 个字符")
	}
	hash, err := secret.HashPassword(newPassword)
	if err != nil {
		return err
	}
	return c.store.UpdateAdminPassword(ctx, admin.Username, hash)
}

func (c *Controller) currentProxyURL(ctx context.Context, settings map[string]string) (string, error) {
	encrypted := strings.TrimSpace(settings["proxy_url_cipher"])
	if encrypted == "" {
		return "", nil
	}
	plain, err := c.secrets.Decrypt(encrypted)
	if err != nil {
		return "", fmt.Errorf("decrypt proxy URL: %w", err)
	}
	return netproxy.Normalize(plain)
}

func (c *Controller) buildRealClient(username string, password string, proxyURL string) (RemoteClient, string, error) {
	accountID := config.AccountIDFor(username)
	client, err := pikpak.NewClient(pikpak.Options{
		Username:   username,
		Password:   password,
		ProxyURL:   proxyURL,
		DataDir:    c.dataDir,
		AccountID:  accountID,
		RatePerSec: config.DefaultAPIRatePerSecond,
		RateBurst:  config.DefaultAPIRateBurst,
	})
	if err != nil {
		return nil, "", err
	}
	return client, accountID, nil
}
