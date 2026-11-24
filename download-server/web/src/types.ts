export interface AuthUser {
  username: string
  csrf_token: string
}

export interface Source {
  id: string
  account_id: string
  remote_root_id: string
  remote_root_path: string
  local_alias: string
  initial_mode: 'download_existing' | 'monitor_new_only'
  baseline_established: boolean
  enabled: boolean
  scan_interval_sec: number
  min_file_size_bytes: number
  last_scan_generation: number
  last_scan_at: number
  last_scan_status: string
  last_scan_error: string
  consecutive_failures: number
  next_scan_at: number
  created_at: number
  updated_at: number
}

export type TaskState =
  | 'QUEUED'
  | 'RUNNING'
  | 'PAUSING'
  | 'PAUSED'
  | 'CANCELING'
  | 'CANCELED'
  | 'RETRY_WAIT'
  | 'VERIFYING'
  | 'COMMITTING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CONFLICT'

export interface TaskView {
  id: string
  source_id: string
  remote_id: string
  version_key: string
  gcid: string
  strong_version: boolean
  remote_path: string
  target_rel_path: string
  state: TaskState
  desired_action: string
  attempt: number
  max_attempts: number
  priority: number
  bytes_total: number
  bytes_done: number
  staging_rel_path: string
  commit_sha256: string
  error_code: string
  error_message: string
  next_retry_at: number
  created_at: number
  updated_at: number
  started_at: number
  finished_at: number
  speed_bytes_per_sec: number
  eta_seconds: number
  progress_percent: number
}

export interface RemoteObject {
  id: string
  source_id: string
  remote_id: string
  parent_remote_id: string
  relative_path: string
  name: string
  is_dir: boolean
  size: number
  gcid: string
  version_key: string
  strong_version: boolean
  remote_modified_at: number
  first_seen_at: number
  last_seen_at: number
  last_seen_generation: number
  miss_count: number
  remote_state: 'PRESENT' | 'OUT_OF_SCOPE'
  missing_since: number
}

export interface Delivery {
  id: string
  source_id: string
  remote_id: string
  version_key: string
  task_id: string
  local_rel_path: string
  size: number
  local_sha256: string
  remote_gcid: string
  verified_remote_hash: boolean
  delivered_at: number
}

export interface FileView {
  remote_object: RemoteObject
  delivery?: Delivery
  latest_task?: TaskView
  path_moved: boolean
}

export interface SystemStats {
  active_workers: number
  queued_count: number
  paused_count: number
  failed_count: number
  completed_count: number
  total_speed_bytes: number
  free_space_bytes: number
  low_disk_blocked: boolean
}

export interface RemoteFolderEntry {
  id: string
  name: string
  is_dir: boolean
  size: number
  mod_time: number
}

export interface SystemSettings {
  download_dir: string
  data_dir: string
  pikpak_username: string
  pikpak_configured: boolean
  max_concurrent_downloads: number
  default_scan_interval_sec: number
  min_free_space_mib: number
  notification_status_interval_minutes: number
}

export interface SetupStatus {
  needs_setup: boolean
  admin_username?: string
  pikpak_username?: string
  pikpak_configured: boolean
  download_dir: string
  data_dir: string
}

export interface LocalDirEntry {
  name: string
  path: string
}

export interface NotificationChannel {
  id: string
  name: string
  type: 'feishu' | 'webhook'
  url_masked: string
  has_secret: boolean
  events: string[]
  enabled: boolean
  created_at: number
  updated_at: number
}
