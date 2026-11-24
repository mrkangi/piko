package pikpak

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/nihildigit/piko/download-server/internal/backend"
)

func TestListPageRetriesSamePageOnCaptchaAndRejectsErrorAsEmpty(t *testing.T) {
	var authCalls atomic.Int32
	var page2Calls atomic.Int32
	var captchaInits atomic.Int32

	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/shield/captcha/init":
			n := captchaInits.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{"captcha_token": "cap_" + string(rune('0'+n))})
		case "/v1/auth/signin":
			authCalls.Add(1)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"access_token":  "tok_1",
				"refresh_token": "ref_1",
				"sub":           "sub_1",
				"expires_in":    3600,
			})
		case "/drive/v1/files":
			page := r.URL.Query().Get("page_token")
			if page == "" {
				_ = json.NewEncoder(w).Encode(map[string]any{
					"next_page_token": "p2",
					"files": []map[string]any{
						{"kind": "drive#file", "id": "f1", "parent_id": "root", "name": "a.mkv", "size": "10", "hash": "AAA"},
					},
				})
				return
			}
			if page == "p2" {
				attempt := page2Calls.Add(1)
				if attempt == 1 {
					_ = json.NewEncoder(w).Encode(map[string]any{"error_code": 9, "error": "captcha_invalid"})
					return
				}
				_ = json.NewEncoder(w).Encode(map[string]any{
					"next_page_token": "",
					"files": []map[string]any{
						{"kind": "drive#file", "id": "f2", "parent_id": "root", "name": "b.mkv", "size": "20", "hash": "BBB"},
					},
				})
				return
			}
		}
	}))
	defer srv.Close()

	client, err := NewClient(Options{
		Username:     "user@example.com",
		Password:     "secret",
		DataDir:      t.TempDir(),
		AccountID:    "acct_test",
		AuthBaseURL:  srv.URL,
		DriveBaseURL: srv.URL,
		RatePerSec:   100,
		RateBurst:    100,
	})
	if err != nil {
		t.Fatal(err)
	}

	p1, err := client.ListPage(context.Background(), "root", "")
	if err != nil || len(p1.Entries) != 1 || p1.NextCursor != "p2" {
		t.Fatalf("unexpected page 1: %+v err=%v", p1, err)
	}
	p2, err := client.ListPage(context.Background(), "root", p1.NextCursor)
	if err != nil {
		t.Fatalf("expected page 2 to succeed after captcha retry, got %v", err)
	}
	if len(p2.Entries) != 1 || p2.Entries[0].ID != "f2" {
		t.Fatalf("unexpected page 2 entries: %+v", p2.Entries)
	}
	if page2Calls.Load() != 2 {
		t.Fatalf("expected exact 2 calls to page 2, got %d", page2Calls.Load())
	}
}

func TestConcurrentRefreshSerializesSingleRotation(t *testing.T) {
	var refreshCalls atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/auth/token":
			refreshCalls.Add(1)
			time.Sleep(30 * time.Millisecond)
			_ = json.NewEncoder(w).Encode(map[string]any{
				"access_token":  "tok_new",
				"refresh_token": "ref_new",
				"sub":           "sub_1",
				"expires_in":    3600,
			})
		case "/drive/v1/files":
			if r.Header.Get("Authorization") != "Bearer tok_new" {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]any{"next_page_token": "", "files": []any{}})
		}
	}))
	defer srv.Close()

	dataDir := t.TempDir()
	store := NewSessionStore(dataDir, "acct_test")
	if err := store.Save(SessionData{
		AccessToken:  "tok_old",
		RefreshToken: "ref_old",
		Sub:          "sub_1",
		DeviceID:     "dev_1",
		ExpiresAt:    time.Now().Add(-time.Minute).Unix(),
	}); err != nil {
		t.Fatal(err)
	}

	client, err := NewClient(Options{
		Username:     "user@example.com",
		Password:     "secret",
		DataDir:      dataDir,
		AccountID:    "acct_test",
		AuthBaseURL:  srv.URL,
		DriveBaseURL: srv.URL,
		RatePerSec:   100,
		RateBurst:    100,
	})
	if err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	for idx := 0; idx < 6; idx++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := client.ListPage(context.Background(), "", ""); err != nil {
				t.Errorf("ListPage failed: %v", err)
			}
		}()
	}
	wg.Wait()
	if refreshCalls.Load() != 1 {
		t.Fatalf("expected 1 serialized token refresh, got %d", refreshCalls.Load())
	}
}

func TestListPageUsesConfiguredHTTPProxy(t *testing.T) {
	var proxyCalls atomic.Int32
	proxySrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proxyCalls.Add(1)
		switch r.URL.Path {
		case "/v1/shield/captcha/init":
			_ = json.NewEncoder(w).Encode(map[string]any{"captcha_token": "cap"})
		case "/v1/auth/signin":
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "tok", "refresh_token": "ref", "expires_in": 3600})
		case "/drive/v1/files":
			_ = json.NewEncoder(w).Encode(map[string]any{"next_page_token": "", "files": []any{}})
		default:
			http.NotFound(w, r)
		}
	}))
	defer proxySrv.Close()

	client, err := NewClient(Options{
		Username:     "user@example.com",
		Password:     "secret",
		ProxyURL:     proxySrv.URL,
		AuthBaseURL:  "http://auth.pikpak.invalid",
		DriveBaseURL: "http://drive.pikpak.invalid",
		RatePerSec:   100,
		RateBurst:    100,
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.ListPage(context.Background(), "", ""); err != nil {
		t.Fatalf("ListPage through HTTP proxy failed: %v", err)
	}
	if proxyCalls.Load() == 0 {
		t.Fatalf("expected requests to use configured HTTP proxy")
	}
}

func TestOpenOriginalRangeValidation(t *testing.T) {
	payload := []byte("0123456789abcdefghij")
	mode := "ok206"
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/shield/captcha/init":
			_ = json.NewEncoder(w).Encode(map[string]any{"captcha_token": "cap"})
		case "/v1/auth/signin":
			_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "tok", "refresh_token": "ref", "expires_in": 3600})
		case "/drive/v1/files/f1":
			_ = json.NewEncoder(w).Encode(map[string]any{
				"kind": "drive#file", "id": "f1", "parent_id": "", "name": "demo.bin",
				"size": "20", "hash": "ABC123",
				"links": map[string]any{"application/octet-stream": map[string]any{"url": srv.URL + "/dl/f1"}},
			})
		case "/dl/f1":
			switch mode {
			case "ok206":
				w.Header().Set("Content-Range", "bytes 10-19/20")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload[10:])
			case "bounded206":
				w.Header().Set("Content-Range", "bytes 10-14/20")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload[10:15])
			case "boundedShort206":
				w.Header().Set("Content-Range", "bytes 10-12/20")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload[10:13])
			case "boundedForced200":
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(payload)
			case "forgedRange":
				w.Header().Set("Content-Range", "bytes 0-19/20")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write(payload)
			case "ignored200":
				w.WriteHeader(http.StatusOK)
				_, _ = w.Write(payload)
			case "416":
				w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			}
		}
	}))
	defer srv.Close()

	client, err := NewClient(Options{
		Username: "user@example.com", Password: "secret",
		AuthBaseURL: srv.URL, DriveBaseURL: srv.URL, RatePerSec: 100, RateBurst: 100,
	})
	if err != nil {
		t.Fatal(err)
	}

	exp := backend.OpenExpectation{
		ObjectID:           "f1",
		ExpectedVersionKey: "gcid:ABC123:20",
		ExpectedSize:       20,
		Offset:             10,
	}
	stream, err := client.OpenOriginal(context.Background(), exp)
	if err != nil {
		t.Fatalf("expected valid 206 stream, got %v", err)
	}
	data, _ := io.ReadAll(stream.Reader)
	_ = stream.Reader.Close()
	if string(data) != "abcdefghij" {
		t.Fatalf("unexpected ranged body %q", string(data))
	}

	mode = "bounded206"
	bounded := exp
	bounded.Length = 5
	stream, err = client.OpenOriginal(context.Background(), bounded)
	if err != nil {
		t.Fatalf("expected valid bounded range, got %v", err)
	}
	data, _ = io.ReadAll(stream.Reader)
	_ = stream.Reader.Close()
	if string(data) != "abcde" {
		t.Fatalf("unexpected bounded range body %q", string(data))
	}

	mode = "boundedShort206"
	stream, err = client.OpenOriginal(context.Background(), bounded)
	if err != nil {
		t.Fatalf("expected shorter bounded range to be accepted, got %v", err)
	}
	data, _ = io.ReadAll(stream.Reader)
	_ = stream.Reader.Close()
	if string(data) != "abc" {
		t.Fatalf("unexpected shorter bounded range body %q", string(data))
	}

	mode = "boundedForced200"
	bounded.Offset = 0
	if _, err := client.OpenOriginal(context.Background(), bounded); backend.KindOf(err) != backend.ErrRangeNotSupported {
		t.Fatalf("expected bounded range to require 206, got %v", err)
	}

	mode = "forgedRange"
	if _, err := client.OpenOriginal(context.Background(), exp); backend.KindOf(err) != backend.ErrRangeInvalid {
		t.Fatalf("expected ErrRangeInvalid on forged Content-Range, got %v", err)
	}

	mode = "ignored200"
	if _, err := client.OpenOriginal(context.Background(), exp); backend.KindOf(err) != backend.ErrRangeNotSupported {
		t.Fatalf("expected ErrRangeNotSupported on 200, got %v", err)
	}

	mode = "416"
	if _, err := client.OpenOriginal(context.Background(), exp); backend.KindOf(err) != backend.ErrRangeInvalid {
		t.Fatalf("expected ErrRangeInvalid on 416, got %v", err)
	}
}
