package pikpak

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
	"github.com/nihildigit/piko/download-server/internal/netproxy"
	"golang.org/x/time/rate"
)

const (
	defaultAuthBaseURL  = "https://user.mypikpak.com"
	defaultDriveBaseURL = "https://api-drive.mypikpak.com"
	userAgent           = "ANDROID-com.pikcloud.pikpak/1.21.0"
	packageName         = "com.pikcloud.pikpak"
	clientVersion       = "1.21.0"
	clientID            = "YNxT9w7GMdWvEOKa"
	clientSecret        = "dbw2OtmVEeuUvIptb1Coyg"
	fileKindFolder      = "drive#folder"
)

var captchaSalts = []string{
	"",
	"E32cSkYXC2bciKJGxRsE8ZgwmH/YwkvpD6/O9guSOa2irCwciH4xPHaH",
	"QtqgfMgHP2TFl",
	"zOKgHT56L7nIzFzDpUGhpWFrgP53m3G6ML",
	"S",
	"THxpsktzfFXizUv7DK1y/N7NZ1WhayViluBEvAJJ8bA1Wr6",
	"y9PXH3xGUhG/zQI8CaapRw2LhldCaFM9CRlKpZXJvj+pifu",
	"+RaaG7T8FRTI4cP019N5y9ofLyHE9ySFUr",
	"6Pf1l8UTeuzYldGtb/d",
}

type Options struct {
	Username     string
	Password     string
	ProxyURL     string
	DataDir      string
	AccountID    string
	AuthBaseURL  string
	DriveBaseURL string
	RatePerSec   float64
	RateBurst    int
	Now          func() time.Time
}

type Client struct {
	opts         Options
	apiHTTP      *http.Client
	streamHTTP   *http.Client
	sessionStore *SessionStore
	limiter      *rate.Limiter
	now          func() time.Time

	authMu       sync.Mutex
	session      SessionData
	captchaToken string
	loaded       bool
}

func NewClient(opts Options) (*Client, error) {
	if strings.TrimSpace(opts.Username) == "" || strings.TrimSpace(opts.Password) == "" {
		return nil, &backend.Error{Kind: backend.ErrAuth, Message: "pikpak username and password are required"}
	}
	if opts.AuthBaseURL == "" {
		opts.AuthBaseURL = defaultAuthBaseURL
	}
	if opts.DriveBaseURL == "" {
		opts.DriveBaseURL = defaultDriveBaseURL
	}
	opts.AuthBaseURL = strings.TrimRight(opts.AuthBaseURL, "/")
	opts.DriveBaseURL = strings.TrimRight(opts.DriveBaseURL, "/")
	if opts.RatePerSec <= 0 {
		opts.RatePerSec = 5
	}
	if opts.RateBurst < 1 {
		opts.RateBurst = 10
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}

	var proxyFunc func(*http.Request) (*url.URL, error)
	if strings.TrimSpace(opts.ProxyURL) != "" {
		normalized, err := netproxy.Normalize(opts.ProxyURL)
		if err != nil {
			return nil, err
		}
		parsed, err := url.Parse(normalized)
		if err != nil {
			return nil, fmt.Errorf("parse proxy url: %w", err)
		}
		opts.ProxyURL = normalized
		proxyFunc = http.ProxyURL(parsed)
	}

	apiTransport := &http.Transport{
		Proxy:               proxyFunc,
		MaxIdleConns:        32,
		MaxIdleConnsPerHost: 16,
		IdleConnTimeout:     90 * time.Second,
	}
	streamTransport := &http.Transport{
		Proxy:               proxyFunc,
		DisableCompression:  true,
		MaxIdleConns:        16,
		MaxIdleConnsPerHost: 8,
		IdleConnTimeout:     90 * time.Second,
	}

	var store *SessionStore
	if strings.TrimSpace(opts.DataDir) != "" && strings.TrimSpace(opts.AccountID) != "" {
		store = NewSessionStore(opts.DataDir, opts.AccountID)
	}

	sum := md5.Sum([]byte(opts.Username))
	deviceID := hex.EncodeToString(sum[:])

	return &Client{
		opts: opts,
		apiHTTP: &http.Client{
			Transport: apiTransport,
			Timeout:   30 * time.Second,
		},
		streamHTTP: &http.Client{
			Transport: streamTransport,
		},
		sessionStore: store,
		limiter:      rate.NewLimiter(rate.Limit(opts.RatePerSec), opts.RateBurst),
		now:          opts.Now,
		session: SessionData{
			DeviceID: deviceID,
		},
	}, nil
}

func (client *Client) Verify(ctx context.Context) error {
	_, _, _, err := client.ensureAuth(ctx, "")
	return err
}

func (client *Client) Probe(ctx context.Context) error {
	_, err := client.ListPage(ctx, "", "")
	return err
}

func (client *Client) ListPage(ctx context.Context, parentID string, cursor string) (backend.ListPageResult, error) {
	filters := `{"trashed":{"eq":false}}`
	query := url.Values{}
	query.Set("thumbnail_size", "SIZE_MEDIUM")
	query.Set("limit", "500")
	query.Set("parent_id", parentID)
	query.Set("with_audit", "false")
	query.Set("filters", filters)
	if cursor != "" {
		query.Set("page_token", cursor)
	}
	endpoint := client.opts.DriveBaseURL + "/drive/v1/files?" + query.Encode()

	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		accessToken, deviceID, captchaToken, err := client.ensureAuth(ctx, "")
		if err != nil {
			return backend.ListPageResult{}, err
		}
		if err := client.limiter.Wait(ctx); err != nil {
			return backend.ListPageResult{}, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return backend.ListPageResult{}, err
		}
		req.Header.Set("Authorization", "Bearer "+accessToken)
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("X-Device-Id", deviceID)
		req.Header.Set("Content-Type", "application/json")
		if captchaToken != "" {
			req.Header.Set("X-Captcha-Token", captchaToken)
		}

		body, status, headers, err := client.doAPI(req)
		if err != nil {
			lastErr = &backend.Error{Kind: backend.ErrTransient, Message: "list request failed", Cause: err}
			continue
		}

		if status == http.StatusUnauthorized {
			if _, _, _, refreshErr := client.ensureAuth(ctx, accessToken); refreshErr != nil {
				return backend.ListPageResult{}, refreshErr
			}
			lastErr = &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: "access token rejected"}
			continue
		}
		if err := classifyAPIResponse(status, headers, body); err != nil {
			if backend.KindOf(err) == backend.ErrCaptcha {
				if capErr := client.refreshActionCaptcha(ctx, "GET:/drive/v1/files", captchaToken); capErr != nil {
					return backend.ListPageResult{}, capErr
				}
				lastErr = err
				continue
			}
			return backend.ListPageResult{}, err
		}

		var payload apiListResponse
		if err := json.Unmarshal(body, &payload); err != nil {
			return backend.ListPageResult{}, &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "invalid list JSON", Cause: err}
		}
		if payload.Files == nil {
			return backend.ListPageResult{}, &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "missing files array in list response"}
		}
		entries := make([]backend.Entry, 0, len(payload.Files))
		for _, raw := range payload.Files {
			entry, convErr := raw.toEntry()
			if convErr != nil {
				return backend.ListPageResult{}, convErr
			}
			entries = append(entries, entry)
		}
		return backend.ListPageResult{
			Entries:    entries,
			NextCursor: payload.NextPageToken,
		}, nil
	}
	return backend.ListPageResult{}, lastErr
}

func (client *Client) Stat(ctx context.Context, objectID string) (backend.Entry, error) {
	detail, err := client.getFileDetail(ctx, objectID)
	if err != nil {
		return backend.Entry{}, err
	}
	return detail.apiFileStat.toEntry()
}

func (client *Client) OpenOriginal(ctx context.Context, expectation backend.OpenExpectation) (*backend.OpenedStream, error) {
	if expectation.Offset < 0 {
		return nil, &backend.Error{Kind: backend.ErrRangeInvalid, Message: "negative offset"}
	}
	if expectation.Length < 0 {
		return nil, &backend.Error{Kind: backend.ErrRangeInvalid, Message: "negative range length"}
	}
	detail, err := client.getFileDetail(ctx, expectation.ObjectID)
	if err != nil {
		return nil, err
	}
	entry, err := detail.apiFileStat.toEntry()
	if err != nil {
		return nil, err
	}
	if entry.IsDir {
		return nil, &backend.Error{Kind: backend.ErrProtocol, Message: "cannot stream directory object"}
	}
	if expectation.ExpectedVersionKey != "" && entry.VersionKey() != expectation.ExpectedVersionKey {
		return nil, &backend.Error{Kind: backend.ErrVersionMismatch, Message: "remote file version changed"}
	}
	if expectation.ExpectedSize >= 0 && entry.Size != expectation.ExpectedSize {
		return nil, &backend.Error{Kind: backend.ErrVersionMismatch, Message: "remote file size changed"}
	}
	if expectation.Offset > 0 && !entry.HasStrongVersion() {
		return nil, &backend.Error{Kind: backend.ErrVersionMismatch, Message: "cannot safely resume without strong content hash"}
	}
	if expectation.Offset > entry.Size {
		return nil, &backend.Error{Kind: backend.ErrRangeInvalid, Message: "resume offset exceeds remote file size"}
	}
	if expectation.Length > 0 && expectation.Offset+expectation.Length > entry.Size {
		return nil, &backend.Error{Kind: backend.ErrRangeInvalid, Message: "requested range exceeds remote file size"}
	}

	rawURL := strings.TrimSpace(detail.Links.ApplicationOctetStream.URL)
	if rawURL == "" {
		return nil, &backend.Error{Kind: backend.ErrProtocol, Message: "original download URL missing"}
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept-Encoding", "identity")
	if expectation.Length > 0 {
		endOffset := expectation.Offset + expectation.Length - 1
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", expectation.Offset, endOffset))
	} else if expectation.Offset > 0 {
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-", expectation.Offset))
	}

	resp, err := client.streamHTTP.Do(req)
	if err != nil {
		return nil, &backend.Error{Kind: backend.ErrTransient, Message: "open download stream failed", Cause: err}
	}

	if err := validateStreamResponse(resp, expectation.Offset, expectation.Length, entry.Size); err != nil {
		_ = resp.Body.Close()
		return nil, err
	}

	return &backend.OpenedStream{
		Reader:          resp.Body,
		Offset:          expectation.Offset,
		TotalSize:       entry.Size,
		VerifiedVersion: entry.VersionKey(),
		Verifiable:      entry.HasStrongVersion(),
	}, nil
}

func validateStreamResponse(resp *http.Response, offset int64, length int64, expectedTotal int64) error {
	switch resp.StatusCode {
	case http.StatusTooManyRequests:
		return &backend.Error{
			Kind:       backend.ErrRateLimited,
			StatusCode: resp.StatusCode,
			RetryAfter: parseRetryAfter(resp.Header.Get("Retry-After")),
			Message:    "download node rate limited",
		}
	case http.StatusRequestedRangeNotSatisfiable:
		return &backend.Error{
			Kind:       backend.ErrRangeInvalid,
			StatusCode: resp.StatusCode,
			Message:    "download node returned 416 range not satisfiable",
		}
	}
	if resp.StatusCode >= 500 {
		return &backend.Error{Kind: backend.ErrTransient, StatusCode: resp.StatusCode, Message: fmt.Sprintf("download node HTTP %d", resp.StatusCode)}
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return &backend.Error{Kind: backend.ErrTransient, StatusCode: resp.StatusCode, Message: fmt.Sprintf("download link rejected HTTP %d", resp.StatusCode)}
	}
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone {
		return &backend.Error{Kind: backend.ErrNotFound, StatusCode: resp.StatusCode, Message: fmt.Sprintf("download target HTTP %d", resp.StatusCode)}
	}

	if offset > 0 || length > 0 {
		if resp.StatusCode == http.StatusOK {
			return &backend.Error{Kind: backend.ErrRangeNotSupported, StatusCode: resp.StatusCode, Message: "download node ignored Range header"}
		}
		if resp.StatusCode != http.StatusPartialContent {
			return &backend.Error{Kind: backend.ErrProtocol, StatusCode: resp.StatusCode, Message: fmt.Sprintf("unexpected ranged status HTTP %d", resp.StatusCode)}
		}
		parsed, err := ParseContentRange(resp.Header.Get("Content-Range"))
		if err != nil {
			return &backend.Error{Kind: backend.ErrRangeInvalid, StatusCode: resp.StatusCode, Message: "invalid Content-Range header", Cause: err}
		}
		if parsed.Start != offset || parsed.Total != expectedTotal {
			return &backend.Error{
				Kind:       backend.ErrRangeInvalid,
				StatusCode: resp.StatusCode,
				Message:    fmt.Sprintf("Content-Range mismatch: got %d-%d/%d expected start=%d total=%d", parsed.Start, parsed.End, parsed.Total, offset, expectedTotal),
			}
		}
		expectedEnd := expectedTotal - 1
		if length > 0 {
			expectedEnd = offset + length - 1
		}
		if length > 0 && parsed.End > expectedEnd {
			return &backend.Error{
				Kind:       backend.ErrRangeInvalid,
				StatusCode: resp.StatusCode,
				Message:    fmt.Sprintf("Content-Range end exceeds requested range: got %d expected <= %d", parsed.End, expectedEnd),
			}
		}
		if length == 0 && parsed.End != expectedEnd {
			return &backend.Error{
				Kind:       backend.ErrRangeInvalid,
				StatusCode: resp.StatusCode,
				Message:    fmt.Sprintf("Content-Range end mismatch: got %d expected %d", parsed.End, expectedEnd),
			}
		}
		expectedSpan := parsed.End - parsed.Start + 1
		if resp.ContentLength >= 0 && resp.ContentLength != expectedSpan {
			return &backend.Error{Kind: backend.ErrRangeInvalid, StatusCode: resp.StatusCode, Message: "Content-Length does not match Content-Range span"}
		}
		return nil
	}

	if resp.StatusCode == http.StatusPartialContent {
		parsed, err := ParseContentRange(resp.Header.Get("Content-Range"))
		if err != nil || parsed.Start != 0 || parsed.Total != expectedTotal || parsed.End != expectedTotal-1 {
			return &backend.Error{Kind: backend.ErrRangeInvalid, StatusCode: resp.StatusCode, Message: "unexpected partial response for full download", Cause: err}
		}
		if resp.ContentLength >= 0 && resp.ContentLength != expectedTotal {
			return &backend.Error{Kind: backend.ErrRangeInvalid, StatusCode: resp.StatusCode, Message: "Content-Length mismatch"}
		}
		return nil
	}
	if resp.StatusCode != http.StatusOK {
		return &backend.Error{Kind: backend.ErrProtocol, StatusCode: resp.StatusCode, Message: fmt.Sprintf("unexpected status HTTP %d", resp.StatusCode)}
	}
	if resp.ContentLength >= 0 && resp.ContentLength != expectedTotal {
		return &backend.Error{Kind: backend.ErrVersionMismatch, StatusCode: resp.StatusCode, Message: "response Content-Length differs from expected file size"}
	}
	return nil
}

func (client *Client) getFileDetail(ctx context.Context, objectID string) (apiFileDetail, error) {
	query := url.Values{}
	query.Set("thumbnail_size", "SIZE_MEDIUM")
	endpoint := client.opts.DriveBaseURL + "/drive/v1/files/" + url.PathEscape(objectID) + "?" + query.Encode()

	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		accessToken, deviceID, captchaToken, err := client.ensureAuth(ctx, "")
		if err != nil {
			return apiFileDetail{}, err
		}
		if err := client.limiter.Wait(ctx); err != nil {
			return apiFileDetail{}, err
		}
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return apiFileDetail{}, err
		}
		req.Header.Set("Authorization", "Bearer "+accessToken)
		req.Header.Set("User-Agent", userAgent)
		req.Header.Set("X-Device-Id", deviceID)
		if captchaToken != "" {
			req.Header.Set("X-Captcha-Token", captchaToken)
		}

		body, status, headers, err := client.doAPI(req)
		if err != nil {
			lastErr = &backend.Error{Kind: backend.ErrTransient, Message: "stat request failed", Cause: err}
			continue
		}
		if status == http.StatusUnauthorized {
			if _, _, _, refreshErr := client.ensureAuth(ctx, accessToken); refreshErr != nil {
				return apiFileDetail{}, refreshErr
			}
			lastErr = &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: "access token rejected"}
			continue
		}
		if err := classifyAPIResponse(status, headers, body); err != nil {
			if backend.KindOf(err) == backend.ErrCaptcha {
				if capErr := client.refreshActionCaptcha(ctx, "GET:/drive/v1/files", captchaToken); capErr != nil {
					return apiFileDetail{}, capErr
				}
				lastErr = err
				continue
			}
			return apiFileDetail{}, err
		}

		var detail apiFileDetail
		if err := json.Unmarshal(body, &detail); err != nil {
			return apiFileDetail{}, &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "invalid file detail JSON", Cause: err}
		}
		if detail.ID == "" {
			return apiFileDetail{}, &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "empty file id in detail response"}
		}
		if detail.Trashed {
			return apiFileDetail{}, &backend.Error{Kind: backend.ErrNotFound, StatusCode: 404, Message: "object is trashed"}
		}
		return detail, nil
	}
	return apiFileDetail{}, lastErr
}

func (client *Client) ensureAuth(ctx context.Context, failedAccessToken string) (string, string, string, error) {
	client.authMu.Lock()
	defer client.authMu.Unlock()

	if !client.loaded && client.sessionStore != nil {
		loaded, err := client.sessionStore.Load()
		if err == nil {
			if loaded.DeviceID == "" {
				loaded.DeviceID = client.session.DeviceID
			}
			client.session = loaded
		} else if !errIsNotExist(err) {
			return "", "", "", &backend.Error{Kind: backend.ErrAuth, Message: "failed to read saved session", Cause: err}
		}
		client.loaded = true
	}

	now := client.now()
	forceRefresh := failedAccessToken != "" && failedAccessToken == client.session.AccessToken
	if !forceRefresh && !client.session.IsExpired(now) {
		return client.session.AccessToken, client.session.DeviceID, client.captchaToken, nil
	}
	if failedAccessToken != "" && failedAccessToken != client.session.AccessToken && !client.session.IsExpired(now) {
		return client.session.AccessToken, client.session.DeviceID, client.captchaToken, nil
	}

	if client.session.RefreshToken != "" {
		err := client.refreshTokenLocked(ctx)
		if err == nil {
			return client.session.AccessToken, client.session.DeviceID, client.captchaToken, nil
		}
		if backend.KindOf(err) != backend.ErrAuth {
			return "", "", "", err
		}
	}

	if err := client.loginLocked(ctx); err != nil {
		return "", "", "", err
	}
	return client.session.AccessToken, client.session.DeviceID, client.captchaToken, nil
}

func (client *Client) refreshTokenLocked(ctx context.Context) error {
	reqBody, err := json.Marshal(map[string]string{
		"client_id":     clientID,
		"client_secret": clientSecret,
		"grant_type":    "refresh_token",
		"refresh_token": client.session.RefreshToken,
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, client.opts.AuthBaseURL+"/v1/auth/token", bytes.NewReader(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Device-Id", client.session.DeviceID)

	body, status, headers, err := client.doAPI(req)
	if err != nil {
		return &backend.Error{Kind: backend.ErrTransient, Message: "refresh token request failed", Cause: err}
	}
	var resp apiTokenResponse
	_ = json.Unmarshal(body, &resp)
	if status == http.StatusUnauthorized || resp.ErrorCode == 4126 || resp.Error == "invalid_grant" {
		return &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: "refresh token rejected"}
	}
	if err := classifyAPIResponse(status, headers, body); err != nil {
		return err
	}
	if resp.AccessToken == "" || resp.RefreshToken == "" {
		return &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "missing tokens in refresh response"}
	}
	client.session.AccessToken = resp.AccessToken
	client.session.RefreshToken = resp.RefreshToken
	if resp.Sub != "" {
		client.session.Sub = resp.Sub
	}
	client.session.ExpiresAt = client.now().Unix() + resp.ExpiresIn
	if client.sessionStore != nil {
		if saveErr := client.sessionStore.Save(client.session); saveErr != nil {
			return &backend.Error{Kind: backend.ErrTransient, Message: "persist refreshed session failed", Cause: saveErr}
		}
	}
	return nil
}

func (client *Client) loginLocked(ctx context.Context) error {
	loginCaptcha, err := client.initLoginCaptchaLocked(ctx)
	if err != nil {
		return err
	}
	reqBody, err := json.Marshal(map[string]string{
		"client_id":     clientID,
		"client_secret": clientSecret,
		"grant_type":    "password",
		"username":      client.opts.Username,
		"password":      client.opts.Password,
		"captcha_token": loginCaptcha,
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, client.opts.AuthBaseURL+"/v1/auth/signin", bytes.NewReader(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Device-Id", client.session.DeviceID)

	body, status, headers, err := client.doAPI(req)
	if err != nil {
		return &backend.Error{Kind: backend.ErrTransient, Message: "login request failed", Cause: err}
	}
	if err := classifyAPIResponse(status, headers, body); err != nil {
		if backend.KindOf(err) == backend.ErrCaptcha {
			return &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: "login blocked by captcha challenge"}
		}
		return err
	}
	var resp apiTokenResponse
	if err := json.Unmarshal(body, &resp); err != nil {
		return &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: "invalid signin JSON", Cause: err}
	}
	if resp.AccessToken == "" || resp.RefreshToken == "" {
		return &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: "missing tokens in signin response"}
	}
	client.session.AccessToken = resp.AccessToken
	client.session.RefreshToken = resp.RefreshToken
	client.session.Sub = resp.Sub
	client.session.ExpiresAt = client.now().Unix() + resp.ExpiresIn
	if client.sessionStore != nil {
		if saveErr := client.sessionStore.Save(client.session); saveErr != nil {
			return &backend.Error{Kind: backend.ErrTransient, Message: "persist session failed", Cause: saveErr}
		}
	}
	return nil
}

func (client *Client) initLoginCaptchaLocked(ctx context.Context) (string, error) {
	reqBody, err := json.Marshal(map[string]any{
		"client_id": clientID,
		"device_id": client.session.DeviceID,
		"action":    "POST:https://user.mypikpak.com/v1/auth/signin",
		"meta": map[string]string{
			"username": client.opts.Username,
		},
	})
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, client.opts.AuthBaseURL+"/v1/shield/captcha/init", bytes.NewReader(reqBody))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Device-Id", client.session.DeviceID)
	body, status, headers, err := client.doAPI(req)
	if err != nil {
		return "", &backend.Error{Kind: backend.ErrTransient, Message: "captcha init request failed", Cause: err}
	}
	if err := classifyAPIResponse(status, headers, body); err != nil {
		return "", err
	}
	var resp apiCaptchaResponse
	if err := json.Unmarshal(body, &resp); err != nil || resp.CaptchaToken == "" {
		return "", &backend.Error{Kind: backend.ErrCaptcha, StatusCode: status, Message: "missing captcha token in init response"}
	}
	return resp.CaptchaToken, nil
}

func (client *Client) refreshActionCaptcha(ctx context.Context, action string, staleToken string) error {
	client.authMu.Lock()
	defer client.authMu.Unlock()

	if client.captchaToken != "" && client.captchaToken != staleToken {
		return nil
	}
	ts := strconv.FormatInt(client.now().UnixMilli(), 10)
	sigInput := clientID + clientVersion + packageName + client.session.DeviceID + ts
	for _, salt := range captchaSalts {
		sum := md5.Sum([]byte(sigInput + salt))
		sigInput = hex.EncodeToString(sum[:])
	}
	reqBody, err := json.Marshal(map[string]any{
		"action":        action,
		"captcha_token": client.captchaToken,
		"client_id":     clientID,
		"device_id":     client.session.DeviceID,
		"meta": map[string]string{
			"captcha_sign":   "1." + sigInput,
			"user_id":        client.session.Sub,
			"package_name":   packageName,
			"client_version": clientVersion,
			"timestamp":      ts,
		},
		"redirect_uri": "ttps://api.mypikpak.com/v1/auth/callback",
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, client.opts.AuthBaseURL+"/v1/shield/captcha/init?client_id="+clientID, bytes.NewReader(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("X-Device-Id", client.session.DeviceID)

	body, status, headers, err := client.doAPI(req)
	if err != nil {
		return &backend.Error{Kind: backend.ErrTransient, Message: "action captcha refresh failed", Cause: err}
	}
	if err := classifyAPIResponse(status, headers, body); err != nil {
		return err
	}
	var resp apiCaptchaResponse
	if err := json.Unmarshal(body, &resp); err != nil || resp.CaptchaToken == "" {
		return &backend.Error{Kind: backend.ErrCaptcha, StatusCode: status, Message: "empty action captcha token"}
	}
	client.captchaToken = resp.CaptchaToken
	return nil
}

func (client *Client) doAPI(req *http.Request) ([]byte, int, http.Header, error) {
	resp, err := client.apiHTTP.Do(req)
	if err != nil {
		return nil, 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8*1024*1024))
	if err != nil {
		return nil, resp.StatusCode, resp.Header, err
	}
	return body, resp.StatusCode, resp.Header, nil
}

func classifyAPIResponse(status int, headers http.Header, body []byte) error {
	var apiErr apiErrorBody
	_ = json.Unmarshal(body, &apiErr)

	if apiErr.ErrorCode == 9 {
		if apiErr.Error == "file_in_recycle_bin" {
			return &backend.Error{Kind: backend.ErrNotFound, StatusCode: status, Message: "object is in recycle bin"}
		}
		return &backend.Error{Kind: backend.ErrCaptcha, StatusCode: status, Message: "captcha verification required"}
	}
	if apiErr.ErrorCode == 3 && strings.Contains(apiErr.Error, "quota") || strings.Contains(apiErr.Error, "monthly_download_size_exceeded") {
		return &backend.Error{Kind: backend.ErrQuotaExceeded, StatusCode: status, Message: apiErr.Error}
	}
	switch status {
	case http.StatusTooManyRequests:
		return &backend.Error{
			Kind:       backend.ErrRateLimited,
			StatusCode: status,
			RetryAfter: parseRetryAfter(headers.Get("Retry-After")),
			Message:    "API rate limited",
		}
	case http.StatusUnauthorized, http.StatusForbidden:
		return &backend.Error{Kind: backend.ErrAuth, StatusCode: status, Message: fmt.Sprintf("authentication failed (HTTP %d)", status)}
	case http.StatusNotFound:
		return &backend.Error{Kind: backend.ErrNotFound, StatusCode: status, Message: "remote object not found"}
	}
	if status >= 500 {
		return &backend.Error{Kind: backend.ErrTransient, StatusCode: status, Message: fmt.Sprintf("upstream server error HTTP %d", status)}
	}
	if status < 200 || status >= 300 {
		return &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: fmt.Sprintf("unexpected API HTTP %d (%s)", status, apiErr.Error)}
	}
	if apiErr.ErrorCode != 0 {
		return &backend.Error{Kind: backend.ErrProtocol, StatusCode: status, Message: fmt.Sprintf("API error code %d (%s)", apiErr.ErrorCode, apiErr.Error)}
	}
	return nil
}

func parseRetryAfter(value string) time.Duration {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return 0
	}
	if secs, err := strconv.Atoi(trimmed); err == nil && secs > 0 {
		return time.Duration(secs) * time.Second
	}
	if when, err := http.ParseTime(trimmed); err == nil {
		if delta := time.Until(when); delta > 0 {
			return delta
		}
	}
	return 0
}

type apiErrorBody struct {
	ErrorCode int    `json:"error_code"`
	Error     string `json:"error"`
}

type apiTokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Sub          string `json:"sub"`
	ExpiresIn    int64  `json:"expires_in"`
	ErrorCode    int    `json:"error_code"`
	Error        string `json:"error"`
}

type apiCaptchaResponse struct {
	CaptchaToken string `json:"captcha_token"`
}

type apiListResponse struct {
	NextPageToken string        `json:"next_page_token"`
	Files         []apiFileStat `json:"files"`
}

type apiFileStat struct {
	Kind         string    `json:"kind"`
	ID           string    `json:"id"`
	ParentID     string    `json:"parent_id"`
	Name         string    `json:"name"`
	Size         string    `json:"size"`
	ModifiedTime time.Time `json:"modified_time"`
	Hash         string    `json:"hash"`
	Trashed      bool      `json:"trashed"`
}

type apiFileDetail struct {
	apiFileStat
	Links struct {
		ApplicationOctetStream struct {
			URL string `json:"url"`
		} `json:"application/octet-stream"`
	} `json:"links"`
}

func (stat apiFileStat) toEntry() (backend.Entry, error) {
	isDir := stat.Kind == fileKindFolder
	size := int64(0)
	if !isDir {
		if stat.Size == "" {
			return backend.Entry{}, &backend.Error{Kind: backend.ErrProtocol, Message: "missing size on remote file"}
		}
		parsed, err := strconv.ParseInt(stat.Size, 10, 64)
		if err != nil || parsed < 0 {
			return backend.Entry{}, &backend.Error{Kind: backend.ErrProtocol, Message: "invalid size on remote file", Cause: err}
		}
		size = parsed
	}
	if strings.TrimSpace(stat.ID) == "" || strings.TrimSpace(stat.Name) == "" {
		return backend.Entry{}, &backend.Error{Kind: backend.ErrProtocol, Message: "missing id or name on remote entry"}
	}
	return backend.Entry{
		ID:       stat.ID,
		ParentID: stat.ParentID,
		Name:     stat.Name,
		IsDir:    isDir,
		Size:     size,
		ModTime:  stat.ModifiedTime.UTC(),
		GCID:     strings.ToUpper(strings.TrimSpace(stat.Hash)),
		Verified: !isDir && strings.TrimSpace(stat.Hash) != "",
	}, nil
}

func IsTransientOrNotFound(err error) bool {
	return errors.Is(err, context.Canceled) || backend.KindOf(err) == backend.ErrTransient
}
