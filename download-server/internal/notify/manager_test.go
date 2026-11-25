package notify

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

func TestFeishuAndWebhookDelivery(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	cipher, err := secret.LoadOrCreate(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	manager := NewManager(st, cipher, events.NewBroker(), nil)

	var feishuPayload map[string]any
	feishuSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &feishuPayload)
		w.WriteHeader(http.StatusOK)
	}))
	defer feishuSrv.Close()

	feishu, err := manager.Create(ctx, Input{
		Name:    "飞书群",
		Type:    TypeFeishu,
		URL:     feishuSrv.URL,
		Secret:  "sign-secret",
		Events:  []string{"task.completed"},
		Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Test(ctx, feishu.ID); err != nil {
		t.Fatalf("feishu test failed: %v", err)
	}
	if feishuPayload["msg_type"] != "text" || feishuPayload["sign"] == nil {
		t.Fatalf("unexpected feishu payload: %+v", feishuPayload)
	}

	var webhookPayload map[string]any
	var authHeader string
	webhookSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader = r.Header.Get("Authorization")
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &webhookPayload)
		w.WriteHeader(http.StatusOK)
	}))
	defer webhookSrv.Close()

	webhook, err := manager.Create(ctx, Input{
		Name:    "通用 Webhook",
		Type:    TypeWebhook,
		URL:     webhookSrv.URL,
		Secret:  "bearer-secret",
		Events:  []string{"task.failed"},
		Enabled: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := manager.Test(ctx, webhook.ID); err != nil {
		t.Fatalf("webhook test failed: %v", err)
	}
	if authHeader != "Bearer bearer-secret" || webhookPayload["event"] != "test" {
		t.Fatalf("unexpected webhook request: auth=%q payload=%+v", authHeader, webhookPayload)
	}

	list, err := manager.List(ctx)
	if err != nil || len(list) != 2 {
		t.Fatalf("unexpected channel list: %+v err=%v", list, err)
	}
	if list[0].HasSecret != true || list[0].URLMasked == feishuSrv.URL {
		t.Fatalf("expected masked URL and secret flag, got %+v", list[0])
	}
}

func TestTaskNotificationContainsCountButNoFileName(t *testing.T) {
	ctx := context.Background()
	dataDir := t.TempDir()
	st, err := store.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	cipher, err := secret.LoadOrCreate(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	manager := NewManager(st, cipher, events.NewBroker(), nil)
	message, err := manager.message(ctx, events.Event{
		Type:  "task.completed",
		Count: 7,
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(message, "7 个任务") {
		t.Fatalf("expected count in notification, got %q", message)
	}
	if strings.Contains(message, "文件") || strings.Contains(message, ".mkv") {
		t.Fatalf("notification must not contain file names, got %q", message)
	}
}

func TestStatusReportContainsQueueSpeedAndRemaining(t *testing.T) {
	message := formatStatus(StatusSnapshot{
		Queued:           3,
		Active:           2,
		Paused:           1,
		RemainingTasks:   6,
		RemainingBytes:   8 * 1024 * 1024 * 1024,
		SpeedBytesPerSec: 4 * 1024 * 1024,
	})
	for _, expected := range []string{"队列等待：3", "进行中：2", "已暂停：1", "剩余任务：6", "8.0 GiB", "4.0 MiB/s", "预计剩余"} {
		if !strings.Contains(message, expected) {
			t.Fatalf("expected %q in status report: %q", expected, message)
		}
	}
	if strings.Contains(message, "来源：") {
		t.Fatalf("status report should not include source: %q", message)
	}
}
