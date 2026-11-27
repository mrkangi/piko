import type { AuthUser, FileListPage, LocalDirEntry, LogPage, NotificationChannel, RemoteFolderEntry, SetupStatus, Source, SystemSettings, SystemStats, TaskListPage, UpdateApplyResult, UpdateInfo } from './types'

const API_BASE = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '')

let csrfToken = ''
let onUnauthorized: (() => void) | null = null

export function setCSRFToken(token: string) {
  csrfToken = token
}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export function apiURL(path: string): string {
  return `${API_BASE}${path}`
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const method = (init.method || 'GET').toUpperCase()
  if (method !== 'GET' && method !== 'HEAD') {
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken)
    if (init.body && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
  }
  const res = await fetch(apiURL(path), {
    ...init,
    headers,
    credentials: 'include',
  })
  const payload = await res.json().catch(() => ({}))
  if (res.status === 401) {
    onUnauthorized?.()
    throw new Error(payload.message || '请先登录')
  }
  if (!res.ok) {
    throw new Error(payload.message || `请求失败 (${res.status})`)
  }
  return payload as T
}

export async function me(): Promise<AuthUser> {
  const data = await request<AuthUser>('/api/auth/me')
  csrfToken = data.csrf_token
  return data
}

export async function login(username: string, password: string): Promise<AuthUser> {
  const data = await request<AuthUser>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  csrfToken = data.csrf_token
  return data
}

export async function logout(): Promise<void> {
  try {
    await request('/api/auth/logout', { method: 'POST' })
  } finally {
    csrfToken = ''
  }
}

export function getSetupStatus(): Promise<SetupStatus> {
  return request<SetupStatus>('/api/setup/status')
}

export function completeSetup(input: {
  token: string
  admin_username: string
  admin_password: string
  pikpak_username: string
  pikpak_password: string
  download_dir: string
}): Promise<AuthUser> {
  return request<AuthUser>('/api/setup', {
    method: 'POST',
    body: JSON.stringify(input),
  }).then((data) => {
    csrfToken = data.csrf_token
    return data
  })
}

export function getStats(): Promise<SystemStats> {
  return request<SystemStats>('/api/system/stats')
}

export function getLogs(params: { limit?: number; before?: number; after?: number } = {}): Promise<LogPage> {
  const qs = new URLSearchParams()
  qs.set('limit', String(params.limit ?? 200))
  if (params.before) qs.set('before', String(params.before))
  if (params.after) qs.set('after', String(params.after))
  return request<LogPage>(`/api/system/logs?${qs.toString()}`)
}

export function listSources(): Promise<{ sources: Source[] }> {
  return request<{ sources: Source[] }>('/api/sources')
}

export function createSource(input: {
  remote_root_id: string
  local_alias: string
  initial_mode: 'download_existing' | 'monitor_new_only'
  scan_interval_sec: number
  min_file_size_bytes: number
}): Promise<Source> {
  return request<Source>('/api/sources', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateSource(id: string, patch: { enabled?: boolean; scan_interval_sec?: number; min_file_size_bytes?: number }): Promise<Source> {
  return request<Source>(`/api/sources/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
}

export function scanSource(id: string): Promise<{ accepted: boolean; source: Source }> {
  return request(`/api/sources/${encodeURIComponent(id)}/scan`, { method: 'POST' })
}

export function scanAllSources(includeDisabled = false): Promise<{ accepted: boolean; scheduled: number }> {
  return request('/api/sources/scan', {
    method: 'POST',
    body: JSON.stringify({ include_disabled: includeDisabled }),
  })
}

export function deleteSource(id: string): Promise<{ deleted: boolean; id: string }> {
  return request(`/api/sources/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function getSettings(): Promise<SystemSettings> {
  return request<SystemSettings>('/api/system/settings')
}

export function getAccount(): Promise<{ pikpak_username: string; pikpak_configured: boolean }> {
  return request('/api/system/account')
}

export function updateAccount(input: { username: string; password: string }): Promise<{ pikpak_username: string; pikpak_configured: boolean }> {
  return request('/api/system/account', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function updateProxy(input: { proxy_url?: string; clear?: boolean }): Promise<{ proxy_configured: boolean; proxy_url_masked: string }> {
  return request('/api/system/proxy', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function changeAdminPassword(input: { current_password: string; new_password: string }): Promise<{ updated: boolean }> {
  return request('/api/system/admin-password', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export interface NotificationInput {
  name?: string
  type?: 'feishu' | 'webhook'
  url?: string
  secret?: string
  events?: string[]
  enabled?: boolean
}

export function listNotifications(): Promise<{ channels: NotificationChannel[] }> {
  return request('/api/notifications')
}

export function createNotification(input: NotificationInput): Promise<NotificationChannel> {
  return request('/api/notifications', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateNotification(id: string, input: NotificationInput): Promise<NotificationChannel> {
  return request(`/api/notifications/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(input),
  })
}

export function deleteNotification(id: string): Promise<{ deleted: boolean }> {
  return request(`/api/notifications/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function testNotification(id: string): Promise<{ sent: boolean }> {
  return request(`/api/notifications/${encodeURIComponent(id)}/test`, { method: 'POST' })
}

export function updateSettings(patch: {
  download_dir?: string
  max_concurrent_downloads?: number
  segment_threads?: number
  segment_min_size_mib?: number
  default_scan_interval_sec?: number
  scan_failure_threshold?: number
  min_free_space_mib?: number
  notification_status_interval_minutes?: number
}): Promise<SystemSettings> {
  return request<SystemSettings>('/api/system/settings', {
    method: 'PUT',
    body: JSON.stringify(patch),
  })
}

export function browseLocalDirs(dirPath = ''): Promise<{ current_path: string; parent_path: string; dirs: LocalDirEntry[] }> {
  const params = new URLSearchParams()
  if (dirPath) params.set('path', dirPath)
  return request(`/api/system/local-dirs?${params.toString()}`)
}

export function browseRemoteFolders(parentId: string, cursor = ''): Promise<{ entries: RemoteFolderEntry[]; next_cursor: string }> {
  const params = new URLSearchParams()
  if (parentId) params.set('parent_id', parentId)
  if (cursor) params.set('cursor', cursor)
  return request(`/api/remote/folders?${params.toString()}`)
}

export function getUpdate(refresh = false): Promise<UpdateInfo> {
  return request<UpdateInfo>(`/api/system/update${refresh ? '?refresh=1' : ''}`)
}

export function applyUpdate(): Promise<UpdateApplyResult> {
  return request<UpdateApplyResult>('/api/system/update/apply', { method: 'POST' })
}

export async function getHealth(): Promise<{ status: string; version: string }> {
  const res = await fetch(apiURL('/healthz'), { credentials: 'include' })
  if (!res.ok) throw new Error(`服务未就绪 (${res.status})`)
  return res.json() as Promise<{ status: string; version: string }>
}

export function listTasks(params: { state?: string; source_id?: string; q?: string; limit?: number; offset?: number }): Promise<TaskListPage> {
  const qs = new URLSearchParams()
  if (params.state) qs.set('state', params.state)
  if (params.source_id) qs.set('source_id', params.source_id)
  if (params.q) qs.set('q', params.q)
  qs.set('limit', String(params.limit ?? 50))
  qs.set('offset', String(params.offset ?? 0))
  return request(`/api/tasks?${qs.toString()}`)
}

export function taskAction(id: string, action: 'pause' | 'resume' | 'cancel' | 'retry'): Promise<{ accepted: boolean }> {
  return request(`/api/tasks/${encodeURIComponent(id)}/${action}`, { method: 'POST' })
}

export function batchTasks(action: 'pause' | 'resume' | 'retry', sourceId = ''): Promise<{ accepted: boolean; affected: number }> {
  return request('/api/tasks/batch', {
    method: 'POST',
    body: JSON.stringify({ action, source_id: sourceId }),
  })
}

export function listFiles(params: { source_id?: string; remote_state?: string; q?: string; limit?: number; offset?: number }): Promise<FileListPage> {
  const qs = new URLSearchParams()
  if (params.source_id) qs.set('source_id', params.source_id)
  if (params.remote_state) qs.set('remote_state', params.remote_state)
  if (params.q) qs.set('q', params.q)
  qs.set('limit', String(params.limit ?? 50))
  qs.set('offset', String(params.offset ?? 0))
  return request(`/api/files?${qs.toString()}`)
}
