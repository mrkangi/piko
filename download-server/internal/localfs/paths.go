package localfs

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"path"
	"strings"

	"golang.org/x/text/unicode/norm"
)

const StagingDirName = ".staging"

var windowsReserved = map[string]struct{}{
	"CON": {}, "PRN": {}, "AUX": {}, "NUL": {},
	"COM1": {}, "COM2": {}, "COM3": {}, "COM4": {}, "COM5": {}, "COM6": {}, "COM7": {}, "COM8": {}, "COM9": {},
	"LPT1": {}, "LPT2": {}, "LPT3": {}, "LPT4": {}, "LPT5": {}, "LPT6": {}, "LPT7": {}, "LPT8": {}, "LPT9": {},
}

func SanitizeAlias(raw string) (string, error) {
	cleaned := sanitizeSegment(raw)
	if cleaned == "" || cleaned == StagingDirName || cleaned == "." || cleaned == ".." {
		return "", fmt.Errorf("invalid source alias")
	}
	return cleaned, nil
}

func MapRelativePath(alias string, remoteRelPath string, objectID string, existingKeys map[string]string) (string, error) {
	safeAlias, err := SanitizeAlias(alias)
	if err != nil {
		return "", err
	}
	parts := strings.Split(strings.ReplaceAll(remoteRelPath, "\\", "/"), "/")
	sanitized := make([]string, 0, len(parts)+1)
	sanitized = append(sanitized, safeAlias)
	for _, part := range parts {
		if part == "" || part == "." {
			continue
		}
		if part == ".." {
			return "", errors.New("path traversal segment not allowed")
		}
		seg := sanitizeSegment(part)
		if seg == "" {
			seg = "_"
		}
		sanitized = append(sanitized, seg)
	}
	if len(sanitized) < 2 {
		return "", errors.New("empty relative file path")
	}
	joined := path.Join(sanitized...)
	if existingKeys != nil {
		normKey := NormalizedCollisionKey(joined)
		if owner, used := existingKeys[normKey]; used && owner != objectID {
			joined = appendCollisionSuffix(joined, objectID)
		}
	}
	return joined, nil
}

func NormalizedCollisionKey(relPath string) string {
	return strings.ToLower(norm.NFC.String(relPath))
}

func appendCollisionSuffix(relPath string, objectID string) string {
	dir := path.Dir(relPath)
	base := path.Base(relPath)
	ext := path.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	sum := sha256.Sum256([]byte(objectID))
	suffix := "-" + hex.EncodeToString(sum[:3])
	if dir == "." {
		return stem + suffix + ext
	}
	return path.Join(dir, stem+suffix+ext)
}

func sanitizeSegment(segment string) string {
	normalized := norm.NFC.String(strings.TrimSpace(segment))
	var builder strings.Builder
	builder.Grow(len(normalized))
	for _, r := range normalized {
		switch {
		case r == 0 || r == '/' || r == '\\' || r < 0x20:
			builder.WriteByte('_')
		case strings.ContainsRune(`<>:"|?*`, r):
			builder.WriteByte('_')
		default:
			builder.WriteRune(r)
		}
	}
	cleaned := strings.TrimRight(builder.String(), ". ")
	if cleaned == "" || cleaned == "." || cleaned == ".." {
		return ""
	}
	if strings.EqualFold(cleaned, StagingDirName) {
		cleaned = "_" + cleaned
	}
	stem := cleaned
	if dot := strings.IndexByte(cleaned, '.'); dot >= 0 {
		stem = cleaned[:dot]
	}
	if _, reserved := windowsReserved[strings.ToUpper(stem)]; reserved {
		cleaned = "_" + cleaned
	}
	return cleaned
}
