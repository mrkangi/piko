package pikpak

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

type SessionData struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Sub          string `json:"sub"`
	DeviceID     string `json:"device_id"`
	ExpiresAt    int64  `json:"expires_at"`
}

type SessionStore struct {
	path string
}

func NewSessionStore(dataDir string, accountID string) *SessionStore {
	return &SessionStore{
		path: filepath.Join(dataDir, fmt.Sprintf("pikpak-session-%s.json", accountID)),
	}
}

func (store *SessionStore) Load() (SessionData, error) {
	raw, err := os.ReadFile(store.path)
	if err != nil {
		return SessionData{}, err
	}
	var data SessionData
	if err := json.Unmarshal(raw, &data); err != nil {
		return SessionData{}, err
	}
	return data, nil
}

func (store *SessionStore) Save(data SessionData) error {
	if err := os.MkdirAll(filepath.Dir(store.path), 0o700); err != nil {
		return err
	}
	raw, err := json.Marshal(data)
	if err != nil {
		return err
	}
	var suffix [6]byte
	_, _ = rand.Read(suffix[:])
	tmpPath := store.path + "." + hex.EncodeToString(suffix[:]) + ".tmp"
	file, err := os.OpenFile(tmpPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := file.Write(raw); err != nil {
		_ = file.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return os.Rename(tmpPath, store.path)
}

func (data SessionData) IsExpired(now time.Time) bool {
	const expirySkewSec = 300
	if data.AccessToken == "" {
		return true
	}
	return now.Unix() >= data.ExpiresAt-expirySkewSec
}

func errIsNotExist(err error) bool {
	return errors.Is(err, os.ErrNotExist)
}
