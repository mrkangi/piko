package store

import "errors"

var (
	ErrNotFound        = errors.New("record not found")
	ErrConflict        = errors.New("state conflict")
	ErrStoppedByIntent = errors.New("stopped by user intent")
	ErrDuplicateAlias  = errors.New("source alias already exists")
)

const (
	InitialModeDownloadExisting = "download_existing"
	InitialModeMonitorNewOnly   = "monitor_new_only"
)

const (
	RemoteStatePresent    = "PRESENT"
	RemoteStateOutOfScope = "OUT_OF_SCOPE"
)

const (
	TaskQueued     = "QUEUED"
	TaskRunning    = "RUNNING"
	TaskPausing    = "PAUSING"
	TaskPaused     = "PAUSED"
	TaskCanceling  = "CANCELING"
	TaskCanceled   = "CANCELED"
	TaskRetryWait  = "RETRY_WAIT"
	TaskVerifying  = "VERIFYING"
	TaskCommitting = "COMMITTING"
	TaskCompleted  = "COMPLETED"
	TaskFailed     = "FAILED"
	TaskConflict   = "CONFLICT"
)

const (
	DesiredNone   = ""
	DesiredPause  = "PAUSE"
	DesiredCancel = "CANCEL"
)

type Source struct {
	ID                  string `json:"id"`
	AccountID           string `json:"account_id"`
	RemoteRootID        string `json:"remote_root_id"`
	RemoteRootPath      string `json:"remote_root_path"`
	LocalAlias          string `json:"local_alias"`
	InitialMode         string `json:"initial_mode"`
	BaselineEstablished bool   `json:"baseline_established"`
	Enabled             bool   `json:"enabled"`
	ScanIntervalSec     int    `json:"scan_interval_sec"`
	MinFileSizeBytes    int64  `json:"min_file_size_bytes"`
	LastScanGeneration  int64  `json:"last_scan_generation"`
	LastScanAt          int64  `json:"last_scan_at"`
	LastScanStatus      string `json:"last_scan_status"`
	LastScanError       string `json:"last_scan_error"`
	ConsecutiveFailures int    `json:"consecutive_failures"`
	NextScanAt          int64  `json:"next_scan_at"`
	CreatedAt           int64  `json:"created_at"`
	UpdatedAt           int64  `json:"updated_at"`
}

type ScanRun struct {
	ID           string `json:"id"`
	SourceID     string `json:"source_id"`
	Generation   int64  `json:"generation"`
	Status       string `json:"status"`
	ErrorMessage string `json:"error_message"`
	EntriesCount int    `json:"entries_count"`
	StartedAt    int64  `json:"started_at"`
	FinishedAt   int64  `json:"finished_at"`
}

type ScanEntry struct {
	ScanRunID        string
	SourceID         string
	RemoteID         string
	ParentRemoteID   string
	RelativePath     string
	Name             string
	IsDir            bool
	Size             int64
	GCID             string
	VersionKey       string
	StrongVersion    bool
	RemoteModifiedAt int64
}

type RemoteObject struct {
	ID                 string `json:"id"`
	SourceID           string `json:"source_id"`
	RemoteID           string `json:"remote_id"`
	ParentRemoteID     string `json:"parent_remote_id"`
	RelativePath       string `json:"relative_path"`
	Name               string `json:"name"`
	IsDir              bool   `json:"is_dir"`
	Size               int64  `json:"size"`
	GCID               string `json:"gcid"`
	VersionKey         string `json:"version_key"`
	BaselineVersionKey string `json:"baseline_version_key,omitempty"`
	StrongVersion      bool   `json:"strong_version"`
	RemoteModifiedAt   int64  `json:"remote_modified_at"`
	FirstSeenAt        int64  `json:"first_seen_at"`
	LastSeenAt         int64  `json:"last_seen_at"`
	LastSeenGeneration int64  `json:"last_seen_generation"`
	MissCount          int    `json:"miss_count"`
	RemoteState        string `json:"remote_state"`
	MissingSince       int64  `json:"missing_since"`
}

type DownloadTask struct {
	ID             string `json:"id"`
	SourceID       string `json:"source_id"`
	RemoteID       string `json:"remote_id"`
	VersionKey     string `json:"version_key"`
	GCID           string `json:"gcid"`
	StrongVersion  bool   `json:"strong_version"`
	RemotePath     string `json:"remote_path"`
	TargetRelPath  string `json:"target_rel_path"`
	State          string `json:"state"`
	DesiredAction  string `json:"desired_action"`
	Attempt        int    `json:"attempt"`
	MaxAttempts    int    `json:"max_attempts"`
	Priority       int    `json:"priority"`
	BytesTotal     int64  `json:"bytes_total"`
	BytesDone      int64  `json:"bytes_done"`
	StagingRelPath string `json:"staging_rel_path"`
	CommitSHA256   string `json:"commit_sha256"`
	ErrorCode      string `json:"error_code"`
	ErrorMessage   string `json:"error_message"`
	NextRetryAt    int64  `json:"next_retry_at"`
	CreatedAt      int64  `json:"created_at"`
	UpdatedAt      int64  `json:"updated_at"`
	StartedAt      int64  `json:"started_at"`
	FinishedAt     int64  `json:"finished_at"`
}

type Delivery struct {
	ID                 string `json:"id"`
	SourceID           string `json:"source_id"`
	RemoteID           string `json:"remote_id"`
	VersionKey         string `json:"version_key"`
	TaskID             string `json:"task_id"`
	LocalRelPath       string `json:"local_rel_path"`
	Size               int64  `json:"size"`
	LocalSHA256        string `json:"local_sha256"`
	RemoteGCID         string `json:"remote_gcid"`
	VerifiedRemoteHash bool   `json:"verified_remote_hash"`
	DeliveredAt        int64  `json:"delivered_at"`
}

type FileView struct {
	RemoteObject RemoteObject  `json:"remote_object"`
	Delivery     *Delivery     `json:"delivery,omitempty"`
	LatestTask   *DownloadTask `json:"latest_task,omitempty"`
	PathMoved    bool          `json:"path_moved"`
}

type AdminSession struct {
	TokenHash string
	Username  string
	CSRFToken string
	ExpiresAt int64
	CreatedAt int64
}

type AdminUser struct {
	ID           int64  `json:"id"`
	Username     string `json:"username"`
	PasswordHash string `json:"-"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
}

type NotificationChannel struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Type         string `json:"type"`
	URLCipher    string `json:"-"`
	SecretCipher string `json:"-"`
	EventsJSON   string `json:"-"`
	Enabled      bool   `json:"enabled"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
}
