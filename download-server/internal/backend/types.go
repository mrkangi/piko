package backend

import (
	"context"
	"errors"
	"fmt"
	"io"
	"strconv"
	"strings"
	"time"
)

type ErrKind string

const (
	ErrAuth              ErrKind = "auth"
	ErrCaptcha           ErrKind = "captcha"
	ErrNotFound          ErrKind = "not_found"
	ErrRateLimited       ErrKind = "rate_limited"
	ErrQuotaExceeded     ErrKind = "quota_exceeded"
	ErrTransient         ErrKind = "transient"
	ErrVersionMismatch   ErrKind = "version_mismatch"
	ErrRangeNotSupported ErrKind = "range_not_supported"
	ErrRangeInvalid      ErrKind = "range_invalid"
	ErrProtocol          ErrKind = "protocol"
)

type Error struct {
	Kind       ErrKind
	StatusCode int
	RetryAfter time.Duration
	Message    string
	Cause      error
}

func (err *Error) Error() string {
	if err == nil {
		return ""
	}
	if err.Cause != nil {
		return fmt.Sprintf("%s: %s: %v", err.Kind, err.Message, err.Cause)
	}
	return fmt.Sprintf("%s: %s", err.Kind, err.Message)
}

func (err *Error) Unwrap() error {
	if err == nil {
		return nil
	}
	return err.Cause
}

func KindOf(err error) ErrKind {
	var backendErr *Error
	if errors.As(err, &backendErr) {
		return backendErr.Kind
	}
	return ""
}

func IsRetryable(err error) bool {
	switch KindOf(err) {
	case ErrTransient, ErrRateLimited, ErrCaptcha:
		return true
	default:
		return false
	}
}

type Entry struct {
	ID       string
	ParentID string
	Name     string
	IsDir    bool
	Size     int64
	ModTime  time.Time
	GCID     string
	Verified bool
}

func (entry Entry) HasStrongVersion() bool {
	return !entry.IsDir && strings.TrimSpace(entry.GCID) != "" && entry.Size >= 0
}

func (entry Entry) VersionKey() string {
	if entry.IsDir {
		return "dir"
	}
	gcid := strings.ToUpper(strings.TrimSpace(entry.GCID))
	if gcid != "" {
		return "gcid:" + gcid + ":" + strconv.FormatInt(entry.Size, 10)
	}
	return "weak:" + strconv.FormatInt(entry.Size, 10) + ":" + strconv.FormatInt(entry.ModTime.UTC().UnixNano(), 10)
}

type ListPageResult struct {
	Entries    []Entry
	NextCursor string
}

type OpenExpectation struct {
	ObjectID           string
	ExpectedVersionKey string
	ExpectedGCID       string
	ExpectedSize       int64
	Offset             int64
	// Length 为 0 时表示从 Offset 一直读到文件末尾；大于 0 时只读取
	// [Offset, Offset+Length) 这一段，供多连接分片下载使用。
	Length int64
}

type OpenedStream struct {
	Reader          io.ReadCloser
	Offset          int64
	TotalSize       int64
	VerifiedVersion string
	Verifiable      bool
}

type Catalog interface {
	ListPage(ctx context.Context, parentID string, cursor string) (ListPageResult, error)
	Stat(ctx context.Context, objectID string) (Entry, error)
}

type ContentSource interface {
	OpenOriginal(ctx context.Context, expectation OpenExpectation) (*OpenedStream, error)
}
