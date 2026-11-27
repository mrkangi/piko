package updater

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	defaultRepo          = "NihilDigit/piko"
	defaultAPIBase       = "https://api.github.com"
	checksumAssetName    = "SHA256SUMS.txt"
	cacheTTL             = 10 * time.Minute
	failedCheckCacheTTL  = time.Minute
	maxReleaseListBytes  = 4 << 20
	maxChecksumFileBytes = 2 << 20
	maxBinaryBytes       = 512 << 20
	minBinaryBytes       = 1 << 20
	maxReleaseNotesBytes = 8 << 10
)

var (
	ErrNoUpdate    = errors.New("already up to date")
	ErrUnsupported = errors.New("online update is not supported")
)

type Info struct {
	CurrentVersion string `json:"current_version"`
	BuildTime      string `json:"build_time,omitempty"`
	Repo           string `json:"repo,omitempty"`
	Channel        string `json:"channel"`
	LatestVersion  string `json:"latest_version,omitempty"`
	LatestTag      string `json:"latest_tag,omitempty"`
	PublishedAt    string `json:"published_at,omitempty"`
	Notes          string `json:"notes,omitempty"`
	ReleaseURL     string `json:"release_url,omitempty"`
	AssetName      string `json:"asset_name,omitempty"`
	AssetSize      int64  `json:"asset_size,omitempty"`
	Available      bool   `json:"available"`
	CanApply       bool   `json:"can_apply"`
	Reason         string `json:"reason,omitempty"`
	CheckError     string `json:"check_error,omitempty"`

	assetURL    string
	checksumURL string
}

type Result struct {
	Version    string `json:"version"`
	Restarting bool   `json:"restarting"`
}

type Service struct {
	version   string
	buildTime time.Time
	repo      string
	apiBase   string
	client    *http.Client
	now       func() time.Time
	restart   func(string) error
	container func() bool

	mu           sync.Mutex
	cached       Info
	cachedAt     time.Time
	cacheTTL     time.Duration
	applying     bool
	lastExecPath string
}

type release struct {
	TagName     string         `json:"tag_name"`
	Name        string         `json:"name"`
	Body        string         `json:"body"`
	Draft       bool           `json:"draft"`
	Prerelease  bool           `json:"prerelease"`
	PublishedAt time.Time      `json:"published_at"`
	CreatedAt   time.Time      `json:"created_at"`
	HTMLURL     string         `json:"html_url"`
	Assets      []releaseAsset `json:"assets"`
}

type releaseAsset struct {
	Name               string `json:"name"`
	Size               int64  `json:"size"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

func New(version, buildTime, repo string) *Service {
	version = strings.TrimSpace(version)
	if version == "" {
		version = "dev"
	}
	repo = strings.Trim(strings.TrimSpace(repo), "/")
	if repo == "" {
		repo = defaultRepo
	}
	parsedBuildTime, _ := time.Parse(time.RFC3339, strings.TrimSpace(buildTime))
	return &Service{
		version:   version,
		buildTime: parsedBuildTime,
		repo:      repo,
		apiBase:   defaultAPIBase,
		client:    &http.Client{Timeout: 10 * time.Minute},
		now:       time.Now,
		restart:   restartProcess,
		container: runningInContainer,
	}
}

func (s *Service) CurrentVersion() string {
	return s.version
}

func (s *Service) Check(ctx context.Context, force bool) (Info, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !force && !s.cachedAt.IsZero() && s.now().Sub(s.cachedAt) < s.cacheTTL {
		return s.cached, nil
	}

	info := s.baseInfo()
	if s.version == "dev" {
		info.Channel = "dev"
		info.Reason = "开发构建不参与在线升级"
		s.remember(info, cacheTTL)
		return info, nil
	}

	releases, err := s.fetchReleases(ctx)
	if err != nil {
		info.CheckError = "检查更新失败：" + err.Error()
		s.remember(info, failedCheckCacheTTL)
		return info, nil
	}

	rel, asset, version, checksums, ok := s.pickRelease(releases)
	if !ok {
		info.Reason = "当前平台没有可用的发布包"
		s.remember(info, cacheTTL)
		return info, nil
	}

	published := rel.PublishedAt
	if published.IsZero() {
		published = rel.CreatedAt
	}
	info.LatestVersion = version
	info.LatestTag = rel.TagName
	info.PublishedAt = published.Format(time.RFC3339)
	info.Notes = truncate(rel.Body, maxReleaseNotesBytes)
	info.ReleaseURL = rel.HTMLURL
	info.AssetName = asset.Name
	info.AssetSize = asset.Size
	info.assetURL = asset.BrowserDownloadURL
	info.checksumURL = checksums.BrowserDownloadURL

	if version == s.version {
		info.Reason = "已是最新版本"
		info.CanApply = false
		s.remember(info, cacheTTL)
		return info, nil
	}
	if !s.releaseIsNewer(rel, published, version) {
		info.Reason = "已是最新版本"
		info.CanApply = false
		s.remember(info, cacheTTL)
		return info, nil
	}

	info.Available = true
	if reason := s.applyBlocker(); reason != "" {
		info.Reason = reason
		info.CanApply = false
	} else {
		info.CanApply = true
	}
	s.remember(info, cacheTTL)
	return info, nil
}

func (s *Service) Apply(ctx context.Context) (Result, error) {
	s.mu.Lock()
	if s.applying {
		s.mu.Unlock()
		return Result{}, errors.New("已有升级正在进行")
	}
	s.applying = true
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.applying = false
		s.mu.Unlock()
	}()

	info, err := s.Check(ctx, true)
	if err != nil {
		return Result{}, err
	}
	if info.CheckError != "" {
		return Result{}, errors.New(info.CheckError)
	}
	if !info.Available {
		return Result{}, ErrNoUpdate
	}
	if !info.CanApply {
		if info.Reason == "" {
			return Result{}, ErrUnsupported
		}
		return Result{}, errors.New(info.Reason)
	}
	if info.assetURL == "" || info.checksumURL == "" || info.AssetName == "" {
		return Result{}, errors.New("发布包信息不完整")
	}

	exe, err := os.Executable()
	if err != nil {
		return Result{}, fmt.Errorf("定位当前程序失败：%w", err)
	}
	exe, err = filepath.Abs(exe)
	if err != nil {
		return Result{}, fmt.Errorf("解析程序路径失败：%w", err)
	}
	if err := s.downloadAndReplace(ctx, exe, info); err != nil {
		return Result{}, err
	}

	s.mu.Lock()
	s.lastExecPath = exe
	s.mu.Unlock()
	return Result{Version: info.LatestVersion, Restarting: true}, nil
}

func (s *Service) ScheduleRestart(delay time.Duration) {
	s.mu.Lock()
	exe := s.lastExecPath
	s.mu.Unlock()
	if exe == "" {
		return
	}

	time.AfterFunc(delay, func() {
		if err := s.restart(exe); err != nil {
			fmt.Fprintf(os.Stderr, "restart after update failed: %v\n", err)
		}
	})
}

func (s *Service) baseInfo() Info {
	info := Info{
		CurrentVersion: s.version,
		Repo:           s.repo,
		Channel:        "stable",
	}
	if !s.buildTime.IsZero() {
		info.BuildTime = s.buildTime.Format(time.RFC3339)
	}
	if strings.Contains(s.version, "-") {
		info.Channel = "test"
	}
	return info
}

func (s *Service) remember(info Info, ttl time.Duration) {
	s.cached = info
	s.cachedAt = s.now()
	s.cacheTTL = ttl
}

func (s *Service) applyBlocker() string {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("DOWNLOAD_SERVER_PACKAGE_MODE"))) {
	case "synology":
		return "当前为群晖套件安装，请通过套件中心升级"
	case "fnos":
		return "当前为飞牛应用，请通过应用中心升级"
	}
	if s.container() {
		return "容器环境不支持自更新，请拉取新镜像后重启容器"
	}
	exe, err := os.Executable()
	if err != nil {
		return "无法定位当前程序，请手动升级"
	}
	probe, err := os.CreateTemp(filepath.Dir(exe), ".piko-update-probe-*")
	if err != nil {
		return "程序目录不可写，请手动升级或使用包管理器"
	}
	name := probe.Name()
	_ = probe.Close()
	_ = os.Remove(name)
	return ""
}

func (s *Service) releaseIsNewer(rel release, published time.Time, version string) bool {
	if !s.buildTime.IsZero() {
		return published.After(s.buildTime)
	}
	return compareVersions(version, s.version) > 0
}

func (s *Service) fetchReleases(ctx context.Context) ([]release, error) {
	endpoint := fmt.Sprintf("%s/repos/%s/releases?per_page=30", s.apiBase, s.repo)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	setGitHubHeaders(req, s.version)

	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("GitHub API 返回 %d：%s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var releases []release
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxReleaseListBytes)).Decode(&releases); err != nil {
		return nil, fmt.Errorf("解析发布列表失败：%w", err)
	}
	sort.SliceStable(releases, func(i, j int) bool {
		return releaseTime(releases[i]).After(releaseTime(releases[j]))
	})
	return releases, nil
}

func (s *Service) pickRelease(releases []release) (release, releaseAsset, string, releaseAsset, bool) {
	includePrerelease := strings.Contains(s.version, "-")
	suffix, ext, platformOK := platformSuffix(runtime.GOOS, runtime.GOARCH, goARM())
	if !platformOK {
		return release{}, releaseAsset{}, "", releaseAsset{}, false
	}

	for _, rel := range releases {
		if rel.Draft || (rel.Prerelease && !includePrerelease) {
			continue
		}
		asset, version, ok := findReleaseAsset(rel.Assets, suffix, ext)
		if !ok {
			continue
		}
		checksums, ok := findAssetByName(rel.Assets, checksumAssetName)
		if !ok {
			continue
		}
		return rel, asset, version, checksums, true
	}
	return release{}, releaseAsset{}, "", releaseAsset{}, false
}

func releaseTime(rel release) time.Time {
	if !rel.PublishedAt.IsZero() {
		return rel.PublishedAt
	}
	return rel.CreatedAt
}

func findReleaseAsset(assets []releaseAsset, suffix, ext string) (releaseAsset, string, bool) {
	for _, asset := range assets {
		if strings.TrimSpace(asset.BrowserDownloadURL) == "" {
			continue
		}
		version := versionFromAsset(asset.Name, suffix, ext)
		if version == "" {
			continue
		}
		return asset, version, true
	}
	return releaseAsset{}, "", false
}

func findAssetByName(assets []releaseAsset, name string) (releaseAsset, bool) {
	for _, asset := range assets {
		if asset.Name == name && strings.TrimSpace(asset.BrowserDownloadURL) != "" {
			return asset, true
		}
	}
	return releaseAsset{}, false
}

func versionFromAsset(name, suffix, ext string) string {
	if ext != "" {
		if !strings.HasSuffix(name, ext) {
			return ""
		}
		name = strings.TrimSuffix(name, ext)
	}
	if !strings.HasSuffix(name, suffix) {
		return ""
	}
	version := strings.TrimSuffix(name, suffix)
	if !strings.HasPrefix(version, "download-server-") {
		return ""
	}
	return strings.TrimPrefix(version, "download-server-")
}

func platformSuffix(goos, goarch, arm string) (suffix, ext string, ok bool) {
	switch goos {
	case "linux":
		switch goarch {
		case "amd64", "arm64", "386":
			return "-linux-" + goarch, "", true
		case "arm":
			if arm == "" || arm == "7" {
				return "-linux-armv7", "", true
			}
		}
	case "darwin":
		if goarch == "amd64" || goarch == "arm64" {
			return "-darwin-" + goarch, "", true
		}
	case "windows":
		if goarch == "amd64" || goarch == "arm64" {
			return "-windows-" + goarch, ".exe", true
		}
	case "freebsd":
		if goarch == "amd64" {
			return "-freebsd-amd64", "", true
		}
	case "android":
		if goarch == "arm64" {
			return "-android-arm64", "", true
		}
	}
	return "", "", false
}

func goARM() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return ""
	}
	for _, setting := range info.Settings {
		if setting.Key == "GOARM" {
			return setting.Value
		}
	}
	return ""
}

func (s *Service) downloadAndReplace(ctx context.Context, exe string, info Info) error {
	checksums, err := s.fetchChecksums(ctx, info.checksumURL)
	if err != nil {
		return err
	}
	expected, ok := checksums[info.AssetName]
	if !ok {
		return fmt.Errorf("校验文件中缺少 %s", info.AssetName)
	}

	dir := filepath.Dir(exe)
	temp, err := os.CreateTemp(dir, ".download-server-update-*")
	if err != nil {
		return fmt.Errorf("创建升级临时文件失败：%w", err)
	}
	tempPath := temp.Name()
	defer func() {
		_ = temp.Close()
		_ = os.Remove(tempPath)
	}()

	hasher := sha256.New()
	written, err := s.download(ctx, info.assetURL, io.MultiWriter(temp, hasher), maxBinaryBytes)
	if err != nil {
		return fmt.Errorf("下载升级包失败：%w", err)
	}
	if written < minBinaryBytes {
		return fmt.Errorf("升级包过小（%d 字节），已中止", written)
	}
	actual := hex.EncodeToString(hasher.Sum(nil))
	if actual != strings.ToLower(expected) {
		return fmt.Errorf("升级包校验失败：期望 %s，实际 %s", expected, actual)
	}
	if err := temp.Sync(); err != nil {
		return fmt.Errorf("写入升级包失败：%w", err)
	}
	if err := temp.Chmod(0o755); err != nil {
		return fmt.Errorf("设置升级包权限失败：%w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("关闭升级包失败：%w", err)
	}

	if err := backupExecutable(exe); err != nil {
		return fmt.Errorf("备份当前程序失败：%w", err)
	}
	if err := replaceExecutable(exe, tempPath); err != nil {
		return fmt.Errorf("替换程序失败：%w", err)
	}
	syncDir(dir)
	return nil
}

func (s *Service) fetchChecksums(ctx context.Context, url string) (map[string]string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	setGitHubHeaders(req, s.version)
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("下载校验文件失败：%w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("下载校验文件失败：HTTP %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxChecksumFileBytes+1))
	if err != nil {
		return nil, fmt.Errorf("读取校验文件失败：%w", err)
	}
	if len(raw) > maxChecksumFileBytes {
		return nil, errors.New("校验文件过大")
	}
	return parseChecksums(string(raw)), nil
}

func (s *Service) download(ctx context.Context, url string, dst io.Writer, limit int64) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, err
	}
	setGitHubHeaders(req, s.version)
	resp, err := s.client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	if resp.ContentLength > limit {
		return 0, fmt.Errorf("升级包过大（%d 字节）", resp.ContentLength)
	}
	written, err := io.Copy(dst, io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return written, err
	}
	if written > limit {
		return written, fmt.Errorf("升级包超过 %d 字节上限", limit)
	}
	return written, nil
}

func parseChecksums(raw string) map[string]string {
	result := make(map[string]string)
	for _, line := range strings.Split(raw, "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) < 2 {
			continue
		}
		sum := strings.ToLower(fields[0])
		if len(sum) != 64 {
			continue
		}
		if _, err := hex.DecodeString(sum); err != nil {
			continue
		}
		name := strings.TrimPrefix(fields[1], "*")
		result[filepath.Base(name)] = sum
	}
	return result
}

func compareVersions(left, right string) int {
	leftParts, leftPre := splitVersion(left)
	rightParts, rightPre := splitVersion(right)
	for i := 0; i < 3; i++ {
		if leftParts[i] < rightParts[i] {
			return -1
		}
		if leftParts[i] > rightParts[i] {
			return 1
		}
	}
	if leftPre == rightPre {
		return 0
	}
	if leftPre == "" {
		return 1
	}
	if rightPre == "" {
		return -1
	}
	return strings.Compare(leftPre, rightPre)
}

func splitVersion(raw string) ([3]int, string) {
	raw = strings.TrimPrefix(strings.TrimSpace(raw), "v")
	core := raw
	pre := ""
	if idx := strings.IndexByte(core, '+'); idx >= 0 {
		core = core[:idx]
	}
	if idx := strings.IndexByte(core, '-'); idx >= 0 {
		pre = core[idx+1:]
		core = core[:idx]
	}
	var parts [3]int
	for i, field := range strings.Split(core, ".") {
		if i >= len(parts) {
			break
		}
		for _, ch := range field {
			if ch < '0' || ch > '9' {
				return parts, pre
			}
			parts[i] = parts[i]*10 + int(ch-'0')
		}
	}
	return parts, pre
}

func setGitHubHeaders(req *http.Request, version string) {
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	req.Header.Set("User-Agent", "piko-download-server/"+version)
}

func truncate(raw string, limit int) string {
	raw = strings.TrimSpace(raw)
	if len(raw) <= limit {
		return raw
	}
	cut := limit
	for cut > 0 && (raw[cut]&0xc0) == 0x80 {
		cut--
	}
	return raw[:cut] + "…"
}

func runningInContainer() bool {
	for _, marker := range []string{"/.dockerenv", "/.containerenv"} {
		if _, err := os.Stat(marker); err == nil {
			return true
		}
	}
	return false
}
