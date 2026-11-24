package secret

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const keyFileName = "secret.key"
const cipherPrefix = "v1:"

type Cipher struct {
	aead cipher.AEAD
}

func LoadOrCreate(dataDir string) (*Cipher, error) {
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}
	keyPath := filepath.Join(dataDir, keyFileName)
	key, err := os.ReadFile(keyPath)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return nil, fmt.Errorf("read secret key: %w", err)
		}
		key = make([]byte, 32)
		if _, randErr := rand.Read(key); randErr != nil {
			return nil, fmt.Errorf("generate secret key: %w", randErr)
		}
		file, createErr := os.OpenFile(keyPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if createErr != nil {
			if errors.Is(createErr, os.ErrExist) {
				key, err = os.ReadFile(keyPath)
				if err != nil {
					return nil, fmt.Errorf("read concurrently created secret key: %w", err)
				}
			} else {
				return nil, fmt.Errorf("create secret key: %w", createErr)
			}
		} else {
			if _, writeErr := file.Write(key); writeErr != nil {
				_ = file.Close()
				return nil, fmt.Errorf("write secret key: %w", writeErr)
			}
			if syncErr := file.Sync(); syncErr != nil {
				_ = file.Close()
				return nil, fmt.Errorf("sync secret key: %w", syncErr)
			}
			if closeErr := file.Close(); closeErr != nil {
				return nil, fmt.Errorf("close secret key: %w", closeErr)
			}
		}
	}
	if len(key) != 32 {
		return nil, fmt.Errorf("secret key must be 32 bytes, got %d", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Cipher{aead: aead}, nil
}

func (c *Cipher) Encrypt(plain string) (string, error) {
	if c == nil || c.aead == nil {
		return "", errors.New("secret cipher is not initialized")
	}
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := c.aead.Seal(nil, nonce, []byte(plain), nil)
	raw := append(nonce, sealed...)
	return cipherPrefix + base64.RawURLEncoding.EncodeToString(raw), nil
}

func (c *Cipher) Decrypt(encoded string) (string, error) {
	if c == nil || c.aead == nil {
		return "", errors.New("secret cipher is not initialized")
	}
	if !strings.HasPrefix(encoded, cipherPrefix) {
		return "", errors.New("unsupported encrypted secret format")
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(encoded, cipherPrefix))
	if err != nil {
		return "", err
	}
	nonceSize := c.aead.NonceSize()
	if len(raw) < nonceSize {
		return "", errors.New("encrypted secret is truncated")
	}
	plain, err := c.aead.Open(nil, raw[:nonceSize], raw[nonceSize:], nil)
	if err != nil {
		return "", errors.New("failed to decrypt secret")
	}
	return string(plain), nil
}
