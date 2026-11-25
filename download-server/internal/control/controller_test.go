package control

import (
	"context"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/catalog"
	"github.com/nihildigit/piko/download-server/internal/config"
	"github.com/nihildigit/piko/download-server/internal/download"
	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/localfs"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

type fakeRemoteClient struct {
	*backend.FakeBackend
}

func (fakeRemoteClient) Verify(context.Context) error { return nil }
func (fakeRemoteClient) Probe(context.Context) error  { return nil }

func newTestController(t *testing.T) (*Controller, *store.Store) {
	t.Helper()
	dataDir := t.TempDir()
	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	fsStore, err := localfs.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = fsStore.Close() })
	secrets, err := secret.LoadOrCreate(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	broker := events.NewBroker()
	runtime := backend.NewRuntime()
	cat := catalog.NewService(st, runtime, broker, "", 5*time.Minute, 5)
	dl := download.NewService(st, fsStore, runtime, runtime, broker, download.Options{MaxConcurrent: 1})
	controller := New(st, secrets, runtime, cat, dl, broker, dataDir)
	fake := backend.NewFakeBackend()
	controller.SetClientFactory(func(username, password, proxyURL string) (RemoteClient, string, error) {
		return fakeRemoteClient{FakeBackend: fake}, config.AccountIDFor(username), nil
	})
	return controller, st
}

func TestSetupCreatesAdminConfiguresRuntimeAndRejectsReplay(t *testing.T) {
	ctx := context.Background()
	controller, st := newTestController(t)
	token, err := controller.EnsureSetupToken(ctx)
	if err != nil || token == "" {
		t.Fatalf("EnsureSetupToken failed: %q %v", token, err)
	}

	admin, err := controller.Setup(ctx, SetupInput{
		Token:          "wrong-token",
		AdminUsername:  "admin",
		AdminPassword:  "password-123",
		PikPakUsername: "user@example.com",
		PikPakPassword: "pikpak-pass",
		DownloadDir:    t.TempDir(),
	})
	if admin.ID != 0 || err != ErrInvalidToken {
		t.Fatalf("expected invalid token rejection, got admin=%+v err=%v", admin, err)
	}

	downloadDir := t.TempDir()
	admin, err = controller.Setup(ctx, SetupInput{
		Token:          token,
		AdminUsername:  "admin",
		AdminPassword:  "password-123",
		PikPakUsername: "user@example.com",
		PikPakPassword: "pikpak-pass",
		DownloadDir:    downloadDir,
	})
	if err != nil {
		t.Fatalf("Setup failed: %v", err)
	}
	if admin.Username != "admin" {
		t.Fatalf("unexpected admin: %+v", admin)
	}
	status, err := controller.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if status.NeedsSetup || !status.PikPakConfigured || status.PikPakUsername != "user@example.com" {
		t.Fatalf("unexpected status after setup: %+v", status)
	}
	if status.DownloadDir != downloadDir {
		t.Fatalf("expected download dir %s, got %s", downloadDir, status.DownloadDir)
	}
	settings, err := st.GetSettings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if settings["pikpak_password_cipher"] == "" || settings["pikpak_password_cipher"] == "pikpak-pass" {
		t.Fatalf("PikPak password must be encrypted at rest")
	}
	if _, err := controller.Setup(ctx, SetupInput{
		Token:          token,
		AdminUsername:  "admin",
		AdminPassword:  "password-123",
		PikPakUsername: "user@example.com",
		PikPakPassword: "pikpak-pass",
		DownloadDir:    downloadDir,
	}); err != ErrSetupAlreadyDone {
		t.Fatalf("expected setup replay rejection, got %v", err)
	}
}

func TestConfigureProxyEncryptsAndAppliesClient(t *testing.T) {
	ctx := context.Background()
	controller, st := newTestController(t)
	token, err := controller.EnsureSetupToken(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := controller.Setup(ctx, SetupInput{
		Token:          token,
		AdminUsername:  "admin",
		AdminPassword:  "password-123",
		PikPakUsername: "user@example.com",
		PikPakPassword: "pikpak-pass",
		DownloadDir:    t.TempDir(),
	}); err != nil {
		t.Fatal(err)
	}

	var appliedProxy string
	fake := backend.NewFakeBackend()
	controller.SetClientFactory(func(username, password, proxyURL string) (RemoteClient, string, error) {
		appliedProxy = proxyURL
		return fakeRemoteClient{FakeBackend: fake}, config.AccountIDFor(username), nil
	})

	if err := controller.ConfigureProxy(ctx, "SOCKS5://alice:secret@127.0.0.1:1080", false); err != nil {
		t.Fatalf("ConfigureProxy failed: %v", err)
	}
	if appliedProxy != "socks5://alice:secret@127.0.0.1:1080" {
		t.Fatalf("client did not receive normalized proxy: %q", appliedProxy)
	}
	status, err := controller.ProxyStatus(ctx)
	if err != nil || !status.Configured || status.MaskedURL != "socks5://alice:***@127.0.0.1:1080" {
		t.Fatalf("unexpected proxy status: %+v err=%v", status, err)
	}
	settings, err := st.GetSettings(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if settings["proxy_url_cipher"] == "" || settings["proxy_url_cipher"] == appliedProxy {
		t.Fatalf("proxy URL must be encrypted at rest: %q", settings["proxy_url_cipher"])
	}

	if err := controller.ConfigureProxy(ctx, "", true); err != nil {
		t.Fatalf("clear proxy failed: %v", err)
	}
	if appliedProxy != "" {
		t.Fatalf("clearing proxy should rebuild direct client, got %q", appliedProxy)
	}
	status, err = controller.ProxyStatus(ctx)
	if err != nil || status.Configured {
		t.Fatalf("expected proxy to be disabled: %+v err=%v", status, err)
	}
}
