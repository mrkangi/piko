package notify

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/events"
	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

const (
	TypeFeishu  = "feishu"
	TypeWebhook = "webhook"
)

var supportedEvents = []string{
	"tasks.queued",
	"task.started",
	"task.retry",
	"task.failed",
	"task.completed",
	"task.conflict",
	"scan.failed",
	"system.low_disk",
	"system.disk_ok",
	"status.report",
}

var DefaultEvents = []string{
	"tasks.queued",
	"task.started",
	"task.failed",
	"task.completed",
	"task.conflict",
	"scan.failed",
	"system.low_disk",
	"status.report",
}

type StatusSnapshot struct {
	Queued           int
	Active           int
	Paused           int
	RemainingTasks   int
	RemainingBytes   int64
	SpeedBytesPerSec int64
}

type StatusProvider func(ctx context.Context) (StatusSnapshot, error)

type ChannelView struct {
	ID        string   `json:"id"`
	Name      string   `json:"name"`
	Type      string   `json:"type"`
	URLMasked string   `json:"url_masked"`
	HasSecret bool     `json:"has_secret"`
	Events    []string `json:"events"`
	Enabled   bool     `json:"enabled"`
	CreatedAt int64    `json:"created_at"`
	UpdatedAt int64    `json:"updated_at"`
}

type Input struct {
	Name    string   `json:"name"`
	Type    string   `json:"type"`
	URL     string   `json:"url"`
	Secret  string   `json:"secret"`
	Events  []string `json:"events"`
	Enabled bool     `json:"enabled"`
}

type Channel struct {
	ID           string
	Name         string
	Type         string
	URL          string
	Secret       string
	URLCipher    string
	SecretCipher string
	EventsJSON   string
	Events       map[string]bool
	Enabled      bool
	CreatedAt    int64
	UpdatedAt    int64
}

type Manager struct {
	store  *store.Store
	cipher *secret.Cipher
	broker *events.Broker
	client *http.Client
	status StatusProvider

	mu       sync.RWMutex
	channels []Channel
	jobs     chan job

	aggregateMu sync.Mutex
	pending     map[string]aggregate
}

type job struct {
	channel Channel
	event   events.Event
}

type aggregate struct {
	channel   Channel
	eventType string
	sourceID  string
	count     int
}

func NewManager(st *store.Store, cipher *secret.Cipher, broker *events.Broker, status StatusProvider) *Manager {
	return &Manager{
		store:   st,
		cipher:  cipher,
		broker:  broker,
		client:  &http.Client{Timeout: 10 * time.Second},
		jobs:    make(chan job, 256),
		pending: make(map[string]aggregate),
		status:  status,
	}
}

func (manager *Manager) Run(ctx context.Context) {
	if err := manager.Reload(ctx); err != nil {
		log.Printf("notification: initial channel load failed: %v", err)
	}
	eventsCh, unsubscribe := manager.broker.Subscribe(256)
	defer unsubscribe()
	go manager.aggregateLoop(ctx)
	go manager.statusLoop(ctx)

	var workers sync.WaitGroup
	for i := 0; i < 4; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case next := <-manager.jobs:
					manager.sendWithRetry(ctx, next.channel, next.event)
				}
			}
		}()
	}
	defer workers.Wait()

	for {
		select {
		case <-ctx.Done():
			return
		case event, open := <-eventsCh:
			if !open {
				return
			}
			manager.enqueue(event)
		}
	}
}

func (manager *Manager) Reload(ctx context.Context) error {
	stored, err := manager.store.ListNotificationChannels(ctx)
	if err != nil {
		return err
	}
	channels := make([]Channel, 0, len(stored))
	for _, item := range stored {
		decoded, err := manager.decodeChannel(item)
		if err != nil {
			log.Printf("notification: skip channel %s: %v", item.ID, err)
			continue
		}
		channels = append(channels, decoded)
	}
	manager.mu.Lock()
	manager.channels = channels
	manager.mu.Unlock()
	return nil
}

func (manager *Manager) List(ctx context.Context) ([]ChannelView, error) {
	stored, err := manager.store.ListNotificationChannels(ctx)
	if err != nil {
		return nil, err
	}
	views := make([]ChannelView, 0, len(stored))
	for _, item := range stored {
		decoded, err := manager.decodeChannel(item)
		if err != nil {
			continue
		}
		views = append(views, manager.view(decoded))
	}
	return views, nil
}

func (manager *Manager) Create(ctx context.Context, input Input) (ChannelView, error) {
	channel, err := manager.fromInput(input, nil)
	if err != nil {
		return ChannelView{}, err
	}
	created, err := manager.store.CreateNotificationChannel(ctx, store.NotificationChannel{
		Name:         channel.Name,
		Type:         channel.Type,
		URLCipher:    channel.URLCipher,
		SecretCipher: channel.SecretCipher,
		EventsJSON:   channel.EventsJSON,
		Enabled:      channel.Enabled,
	})
	if err != nil {
		return ChannelView{}, err
	}
	_ = manager.Reload(ctx)
	decoded, err := manager.decodeChannel(created)
	if err != nil {
		return ChannelView{}, err
	}
	return manager.view(decoded), nil
}

func (manager *Manager) Update(ctx context.Context, id string, input Input) (ChannelView, error) {
	existing, err := manager.store.GetNotificationChannel(ctx, id)
	if err != nil {
		return ChannelView{}, err
	}
	channel, err := manager.fromInput(input, &existing)
	if err != nil {
		return ChannelView{}, err
	}
	channel.ID = id
	updated, err := manager.store.UpdateNotificationChannel(ctx, store.NotificationChannel{
		ID:           id,
		Name:         channel.Name,
		Type:         channel.Type,
		URLCipher:    channel.URLCipher,
		SecretCipher: channel.SecretCipher,
		EventsJSON:   channel.EventsJSON,
		Enabled:      channel.Enabled,
	})
	if err != nil {
		return ChannelView{}, err
	}
	_ = manager.Reload(ctx)
	decoded, err := manager.decodeChannel(updated)
	if err != nil {
		return ChannelView{}, err
	}
	return manager.view(decoded), nil
}

func (manager *Manager) Delete(ctx context.Context, id string) error {
	if err := manager.store.DeleteNotificationChannel(ctx, id); err != nil {
		return err
	}
	return manager.Reload(ctx)
}

func (manager *Manager) Test(ctx context.Context, id string) error {
	channel, err := manager.channel(id)
	if err != nil {
		return err
	}
	return manager.send(ctx, channel, events.Event{
		Type:      "test",
		Message:   fmt.Sprintf("Piko Download Server 测试通知\n渠道：%s\n通知渠道配置成功。", channel.Name),
		Timestamp: time.Now().Unix(),
	})
}

func (manager *Manager) channel(id string) (Channel, error) {
	manager.mu.RLock()
	defer manager.mu.RUnlock()
	for _, channel := range manager.channels {
		if channel.ID == id {
			return channel, nil
		}
	}
	return Channel{}, store.ErrNotFound
}

func (manager *Manager) fromInput(input Input, existing *store.NotificationChannel) (Channel, error) {
	name := strings.TrimSpace(input.Name)
	channelType := strings.TrimSpace(input.Type)
	if existing != nil {
		if name == "" {
			name = existing.Name
		}
		if channelType == "" {
			channelType = existing.Type
		}
	}
	if name == "" {
		return Channel{}, errors.New("通知渠道名称不能为空")
	}
	if channelType != TypeFeishu && channelType != TypeWebhook {
		return Channel{}, errors.New("通知类型必须是 feishu 或 webhook")
	}
	rawURL := strings.TrimSpace(input.URL)
	if rawURL == "" && existing != nil {
		rawURL = ""
	}
	if rawURL != "" {
		parsed, err := url.Parse(rawURL)
		if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
			return Channel{}, errors.New("Webhook URL 必须是 http/https 地址")
		}
	} else if existing == nil {
		return Channel{}, errors.New("Webhook URL 不能为空")
	}
	eventsList := sanitizeEvents(input.Events)
	if len(eventsList) == 0 {
		if existing != nil {
			_ = json.Unmarshal([]byte(existing.EventsJSON), &eventsList)
			eventsList = sanitizeEvents(eventsList)
		}
		if len(eventsList) == 0 {
			eventsList = append([]string(nil), DefaultEvents...)
		}
	}
	eventsJSON, _ := json.Marshal(eventsList)
	channel := Channel{
		Name:      name,
		Type:      channelType,
		URL:       rawURL,
		Secret:    input.Secret,
		Events:    eventSet(eventsList),
		Enabled:   input.Enabled,
		CreatedAt: time.Now().Unix(),
	}
	if existing != nil {
		channel.CreatedAt = existing.CreatedAt
		if rawURL == "" {
			channel.URL, _ = manager.cipher.Decrypt(existing.URLCipher)
			channel.URLCipher = existing.URLCipher
		}
		if input.Secret == "" {
			channel.Secret, _ = manager.cipher.Decrypt(existing.SecretCipher)
			channel.SecretCipher = existing.SecretCipher
		}
	}
	if channel.URLCipher == "" {
		encrypted, err := manager.cipher.Encrypt(channel.URL)
		if err != nil {
			return Channel{}, err
		}
		channel.URLCipher = encrypted
	}
	if channel.SecretCipher == "" && channel.Secret != "" {
		encrypted, err := manager.cipher.Encrypt(channel.Secret)
		if err != nil {
			return Channel{}, err
		}
		channel.SecretCipher = encrypted
	}
	channel.EventsJSON = string(eventsJSON)
	return channel, nil
}

func (manager *Manager) decodeChannel(item store.NotificationChannel) (Channel, error) {
	rawURL, err := manager.cipher.Decrypt(item.URLCipher)
	if err != nil {
		return Channel{}, err
	}
	rawSecret, err := manager.cipher.Decrypt(item.SecretCipher)
	if err != nil && strings.TrimSpace(item.SecretCipher) != "" {
		return Channel{}, err
	}
	var eventsList []string
	if err := json.Unmarshal([]byte(item.EventsJSON), &eventsList); err != nil {
		eventsList = append([]string(nil), DefaultEvents...)
	}
	eventsList = sanitizeEvents(eventsList)
	return Channel{
		ID:           item.ID,
		Name:         item.Name,
		Type:         item.Type,
		URL:          rawURL,
		Secret:       rawSecret,
		URLCipher:    item.URLCipher,
		SecretCipher: item.SecretCipher,
		EventsJSON:   item.EventsJSON,
		Events:       eventSet(eventsList),
		Enabled:      item.Enabled,
		CreatedAt:    item.CreatedAt,
		UpdatedAt:    item.UpdatedAt,
	}, nil
}

func (manager *Manager) view(channel Channel) ChannelView {
	return ChannelView{
		ID:        channel.ID,
		Name:      channel.Name,
		Type:      channel.Type,
		URLMasked: maskURL(channel.URL),
		HasSecret: channel.Secret != "",
		Events:    sortedEvents(channel.Events),
		Enabled:   channel.Enabled,
		CreatedAt: channel.CreatedAt,
		UpdatedAt: channel.UpdatedAt,
	}
}

func (manager *Manager) enqueue(event events.Event) {
	manager.mu.RLock()
	channels := append([]Channel(nil), manager.channels...)
	manager.mu.RUnlock()
	for _, channel := range channels {
		if !channel.Enabled || !channel.Events[event.Type] {
			continue
		}
		if aggregateEvent(event.Type) {
			manager.addAggregate(channel, event)
			continue
		}
		select {
		case manager.jobs <- job{channel: channel, event: event}:
		default:
			log.Printf("notification: queue full, dropping %s for %s", event.Type, channel.ID)
		}
	}
}

func (manager *Manager) addAggregate(channel Channel, event events.Event) {
	key := channel.ID + "|" + event.SourceID + "|" + event.Type
	count := event.Count
	if count <= 0 {
		count = 1
	}
	manager.aggregateMu.Lock()
	item := manager.pending[key]
	item.channel = channel
	item.eventType = event.Type
	item.sourceID = event.SourceID
	item.count += count
	manager.pending[key] = item
	manager.aggregateMu.Unlock()
}

func (manager *Manager) aggregateLoop(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			manager.flushAggregates()
			return
		case <-ticker.C:
			manager.flushAggregates()
		}
	}
}

func (manager *Manager) statusLoop(ctx context.Context) {
	if manager.status == nil {
		return
	}
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	var lastSent time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			interval := manager.statusInterval(ctx)
			if interval <= 0 {
				lastSent = time.Time{}
				continue
			}
			if !lastSent.IsZero() && time.Since(lastSent) < interval {
				continue
			}
			snapshot, err := manager.status(ctx)
			if err != nil {
				log.Printf("notification: status snapshot failed: %v", err)
				continue
			}
			if snapshot.RemainingTasks == 0 && snapshot.Active == 0 {
				continue
			}
			manager.enqueue(events.Event{
				Type:    "status.report",
				Count:   snapshot.RemainingTasks,
				Message: formatStatus(snapshot),
			})
			lastSent = time.Now()
		}
	}
}

func (manager *Manager) statusInterval(ctx context.Context) time.Duration {
	settings, err := manager.store.GetSettings(ctx)
	if err != nil {
		return 0
	}
	raw := strings.TrimSpace(settings["notification_status_interval_minutes"])
	if raw == "" {
		raw = "30"
	}
	minutes, err := strconv.Atoi(raw)
	if err != nil || minutes <= 0 {
		return 0
	}
	return time.Duration(minutes) * time.Minute
}

func (manager *Manager) flushAggregates() {
	manager.aggregateMu.Lock()
	pending := manager.pending
	manager.pending = make(map[string]aggregate)
	manager.aggregateMu.Unlock()
	for _, item := range pending {
		event := events.Event{
			Type:     item.eventType,
			SourceID: item.sourceID,
			Count:    item.count,
			Message:  manager.taskCountMessage(context.Background(), item.eventType, item.sourceID, item.count),
		}
		select {
		case manager.jobs <- job{channel: item.channel, event: event}:
		default:
			log.Printf("notification: queue full, dropping aggregate %s for %s", item.eventType, item.channel.ID)
		}
	}
}

func aggregateEvent(eventType string) bool {
	switch eventType {
	case "task.started", "task.completed", "task.failed", "task.retry", "task.conflict":
		return true
	default:
		return false
	}
}

func (manager *Manager) sendWithRetry(ctx context.Context, channel Channel, event events.Event) {
	message, err := manager.message(ctx, event)
	if err != nil {
		log.Printf("notification: build message failed: %v", err)
		return
	}
	for attempt := 1; attempt <= 3; attempt++ {
		err = manager.sendMessage(ctx, channel, event, message)
		if err == nil {
			return
		}
		if attempt == 3 {
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Duration(attempt) * 2 * time.Second):
		}
	}
	log.Printf("notification: send failed for %s after retries: %v", channel.ID, err)
}

func (manager *Manager) send(ctx context.Context, channel Channel, event events.Event) error {
	message, err := manager.message(ctx, event)
	if err != nil {
		return err
	}
	return manager.sendMessage(ctx, channel, event, message)
}

func (manager *Manager) sendMessage(ctx context.Context, channel Channel, event events.Event, message string) error {
	var payload any
	headers := map[string]string{"Content-Type": "application/json"}
	switch channel.Type {
	case TypeFeishu:
		body := map[string]any{
			"msg_type": "text",
			"content":  map[string]string{"text": message},
		}
		if channel.Secret != "" {
			timestamp := time.Now().Unix()
			body["timestamp"] = strconv.FormatInt(timestamp, 10)
			body["sign"] = feishuSign(timestamp, channel.Secret)
		}
		payload = body
	case TypeWebhook:
		payload = map[string]any{
			"event":     event.Type,
			"message":   message,
			"count":     event.Count,
			"timestamp": time.Now().Unix(),
			"source_id": event.SourceID,
			"task_id":   event.TaskID,
		}
		if channel.Secret != "" {
			headers["Authorization"] = "Bearer " + channel.Secret
		}
	default:
		return fmt.Errorf("unsupported notification type: %s", channel.Type)
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, channel.URL, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := manager.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	rawResp, _ := io.ReadAll(io.LimitReader(resp.Body, 32*1024))
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("webhook returned HTTP %d", resp.StatusCode)
	}
	if channel.Type == TypeFeishu && len(rawResp) > 0 {
		var result struct {
			Code int    `json:"code"`
			Msg  string `json:"msg"`
		}
		if err := json.Unmarshal(rawResp, &result); err == nil && result.Code != 0 {
			return fmt.Errorf("feishu returned code %d: %s", result.Code, result.Msg)
		}
	}
	return nil
}

func (manager *Manager) message(ctx context.Context, event events.Event) (string, error) {
	if event.Type == "tasks.queued" || strings.HasPrefix(event.Type, "system.") {
		return event.Message, nil
	}
	if event.Type == "test" {
		return event.Message, nil
	}
	if event.Type == "status.report" {
		return event.Message, nil
	}
	if event.Type == "scan.failed" {
		sourceName := event.SourceID
		if source, err := manager.store.GetSource(ctx, event.SourceID); err == nil {
			sourceName = source.LocalAlias
		}
		text := fmt.Sprintf("来源扫描连续失败\n来源：%s", sourceName)
		if event.Count > 0 {
			text += fmt.Sprintf("\n连续失败：%d 次", event.Count)
		}
		return text + "\n详情请在管理页查看", nil
	}
	if strings.HasPrefix(event.Type, "task.") {
		count := event.Count
		if count <= 0 {
			count = 1
		}
		return manager.taskCountMessage(ctx, event.Type, event.SourceID, count), nil
	}
	return event.Message, nil
}

func (manager *Manager) taskCountMessage(ctx context.Context, eventType, sourceID string, count int) string {
	sourceName := sourceID
	if source, sourceErr := manager.store.GetSource(ctx, sourceID); sourceErr == nil {
		sourceName = source.LocalAlias
	}
	header := map[string]string{
		"task.started":   "开始下载",
		"task.retry":     "下载失败，准备重试",
		"task.failed":    "下载失败",
		"task.completed": "下载完成",
		"task.conflict":  "路径冲突",
	}[eventType]
	if header == "" {
		header = eventType
	}
	text := fmt.Sprintf("%s\n数量：%d 个任务", header, count)
	if sourceName != "" {
		text += "\n来源：" + sourceName
	}
	return text
}

func sanitizeEvents(values []string) []string {
	seen := make(map[string]bool)
	var out []string
	for _, value := range values {
		for _, supported := range supportedEvents {
			if value == supported && !seen[value] {
				seen[value] = true
				out = append(out, value)
			}
		}
	}
	sort.Strings(out)
	return out
}

func eventSet(values []string) map[string]bool {
	out := make(map[string]bool, len(values))
	for _, value := range values {
		out[value] = true
	}
	return out
}

func sortedEvents(values map[string]bool) []string {
	out := make([]string, 0, len(values))
	for value, enabled := range values {
		if enabled {
			out = append(out, value)
		}
	}
	sort.Strings(out)
	return out
}

func maskURL(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "已配置"
	}
	return parsed.Scheme + "://" + parsed.Host + "/…"
}

func formatStatus(snapshot StatusSnapshot) string {
	text := fmt.Sprintf("Piko 下载状态\n队列等待：%d\n进行中：%d\n已暂停：%d\n剩余任务：%d\n剩余大小：%s\n当前速度：%s",
		snapshot.Queued,
		snapshot.Active,
		snapshot.Paused,
		snapshot.RemainingTasks,
		humanBytes(snapshot.RemainingBytes),
		humanSpeed(snapshot.SpeedBytesPerSec),
	)
	if snapshot.SpeedBytesPerSec > 0 && snapshot.RemainingBytes > 0 {
		eta := time.Duration(snapshot.RemainingBytes/snapshot.SpeedBytesPerSec) * time.Second
		text += "\n预计剩余：" + formatDuration(eta)
	}
	return text
}

func humanBytes(bytes int64) string {
	const unit = 1024
	if bytes < unit {
		return fmt.Sprintf("%d B", bytes)
	}
	div, exp := int64(unit), 0
	for n := bytes / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(bytes)/float64(div), "KMGTPE"[exp])
}

func humanSpeed(bytesPerSecond int64) string {
	if bytesPerSecond <= 0 {
		return "0 B/s"
	}
	return humanBytes(bytesPerSecond) + "/s"
}

func formatDuration(duration time.Duration) string {
	if duration < time.Minute {
		return fmt.Sprintf("%d 秒", int(duration.Seconds()))
	}
	if duration < time.Hour {
		return fmt.Sprintf("%d 分 %d 秒", int(duration.Minutes()), int(duration.Seconds())%60)
	}
	return fmt.Sprintf("%d 小时 %d 分", int(duration.Hours()), int(duration.Minutes())%60)
}

func feishuSign(timestamp int64, secretValue string) string {
	key := strconv.FormatInt(timestamp, 10) + "\n" + secretValue
	mac := hmac.New(sha256.New, []byte(key))
	_, _ = mac.Write(nil)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}
