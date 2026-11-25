package web

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/nihildigit/piko/download-server/internal/secret"
	"github.com/nihildigit/piko/download-server/internal/store"
)

const sessionCookieName = "piko_ds_session"
const sessionTTL = 7 * 24 * time.Hour

type Authenticator struct {
	store        *store.Store
	trustProxy   bool
	cookieSecure bool
}

func NewAuthenticator(st *store.Store, trustProxy, cookieSecure bool) *Authenticator {
	return &Authenticator{
		store:        st,
		trustProxy:   trustProxy,
		cookieSecure: cookieSecure,
	}
}

func (auth *Authenticator) VerifyCredentials(ctx context.Context, username, password string) (bool, error) {
	admin, err := auth.store.GetAdmin(ctx)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return false, nil
		}
		return false, err
	}
	userOK := subtle.ConstantTimeCompare([]byte(strings.TrimSpace(username)), []byte(admin.Username)) == 1
	return userOK && secret.CheckPassword(admin.PasswordHash, password), nil
}

func (auth *Authenticator) CreateSession(r *http.Request, w http.ResponseWriter, username string) (store.AdminSession, error) {
	var rawToken [32]byte
	var rawCSRF [24]byte
	_, _ = rand.Read(rawToken[:])
	_, _ = rand.Read(rawCSRF[:])

	plainToken := hex.EncodeToString(rawToken[:])
	tokenHash := hashToken(plainToken)
	now := time.Now()
	sess := store.AdminSession{
		TokenHash: tokenHash,
		Username:  username,
		CSRFToken: hex.EncodeToString(rawCSRF[:]),
		ExpiresAt: now.Add(sessionTTL).Unix(),
		CreatedAt: now.Unix(),
	}
	if err := auth.store.CreateAdminSession(r.Context(), sess); err != nil {
		return store.AdminSession{}, err
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    plainToken,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   auth.isSecureRequest(r),
		MaxAge:   int(sessionTTL.Seconds()),
	})
	return sess, nil
}

func (auth *Authenticator) Authenticate(r *http.Request) (store.AdminSession, bool) {
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil || strings.TrimSpace(cookie.Value) == "" {
		return store.AdminSession{}, false
	}
	sess, err := auth.store.GetAdminSession(r.Context(), hashToken(cookie.Value))
	if err != nil {
		return store.AdminSession{}, false
	}
	if time.Now().Unix() >= sess.ExpiresAt {
		_ = auth.store.DeleteAdminSession(r.Context(), sess.TokenHash)
		return store.AdminSession{}, false
	}
	return sess, true
}

func (auth *Authenticator) ClearSession(r *http.Request, w http.ResponseWriter) {
	if cookie, err := r.Cookie(sessionCookieName); err == nil && cookie.Value != "" {
		_ = auth.store.DeleteAdminSession(r.Context(), hashToken(cookie.Value))
	}
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   auth.isSecureRequest(r),
		MaxAge:   -1,
	})
}

func (auth *Authenticator) CheckCSRF(r *http.Request, sess store.AdminSession) bool {
	headerToken := strings.TrimSpace(r.Header.Get("X-CSRF-Token"))
	if headerToken == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(headerToken), []byte(sess.CSRFToken)) == 1
}

func (auth *Authenticator) CheckOrigin(r *http.Request, allowedCORSOrigin string) bool {
	origin := strings.TrimSpace(r.Header.Get("Origin"))
	if origin == "" {
		return true
	}
	if allowedCORSOrigin != "" && origin == allowedCORSOrigin {
		return true
	}
	expectedHost := r.Host
	if expectedHost != "" && strings.HasSuffix(origin, "://"+expectedHost) {
		return true
	}
	return false
}

func (auth *Authenticator) isSecureRequest(r *http.Request) bool {
	if auth.cookieSecure || r.TLS != nil {
		return true
	}
	if auth.trustProxy && strings.EqualFold(strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")), "https") {
		return true
	}
	return false
}

func hashToken(plain string) string {
	sum := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(sum[:])
}
