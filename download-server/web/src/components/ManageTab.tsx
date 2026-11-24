import { useEffect, useState, type FormEvent } from 'react'
import {
  FolderCog,
  FolderOpen,
  HardDrive,
  Plus,
  Power,
  RefreshCw,
  Save,
  Trash2,
  CheckSquare,
  UserCog,
  ShieldCheck,
  Bell,
  Send,
} from 'lucide-react'
import type { NotificationChannel, Source, SystemSettings } from '../types'
import { formatRelativeTime, formatTimestamp } from '../format'
import { LocalDirModal } from './LocalDirModal'
import { RemoteFolderModal, type PickedRemoteFolder } from './RemoteFolderModal'

interface DraftSource {
  key: string
  remoteRootId: string
  remotePathHint: string
  localAlias: string
  initialMode: 'download_existing' | 'monitor_new_only'
  scanIntervalSec: number
  minFileSizeMiB: number
}

const NOTIFY_EVENTS = [
  { key: 'tasks.queued', label: '加入下载队列' },
  { key: 'task.started', label: '开始下载' },
  { key: 'task.retry', label: '失败后重试' },
  { key: 'task.failed', label: '下载最终失败' },
  { key: 'task.completed', label: '下载完成' },
  { key: 'task.conflict', label: '本地路径冲突' },
  { key: 'scan.failed', label: '来源扫描失败' },
  { key: 'system.low_disk', label: '磁盘空间不足' },
  { key: 'system.disk_ok', label: '磁盘空间恢复' },
  { key: 'status.report', label: '定时状态汇报' },
]

const NOTIFY_DEFAULT_EVENTS = [
  'tasks.queued',
  'task.started',
  'task.failed',
  'task.completed',
  'task.conflict',
  'scan.failed',
  'system.low_disk',
  'status.report',
]

interface Props {
  settings: SystemSettings | null
  sources: Source[]
  notificationChannels: NotificationChannel[]
  activeWorkers: number
  onSaveSettings: (patch: {
    download_dir?: string
    max_concurrent_downloads?: number
    default_scan_interval_sec?: number
    min_free_space_mib?: number
  }) => Promise<void>
  onCreateSources: (
    items: Array<{
      remote_root_id: string
      local_alias: string
      initial_mode: 'download_existing' | 'monitor_new_only'
      scan_interval_sec: number
      min_file_size_bytes: number
    }>,
  ) => Promise<void>
  onUpdateSource: (id: string, patch: { enabled?: boolean; scan_interval_sec?: number; min_file_size_bytes?: number }) => Promise<void>
  onScanSource: (id: string) => Promise<void>
  onScanAllSources: () => Promise<void>
  onDeleteSource: (id: string) => Promise<void>
  onUpdateAccount: (username: string, password: string) => Promise<void>
  onChangeAdminPassword: (currentPassword: string, newPassword: string) => Promise<void>
  onCreateNotification: (input: {
    name: string
    type: 'feishu' | 'webhook'
    url: string
    secret: string
    events: string[]
    enabled: boolean
  }) => Promise<void>
  onUpdateNotification: (id: string, input: { enabled?: boolean }) => Promise<void>
  onDeleteNotification: (id: string) => Promise<void>
  onTestNotification: (id: string) => Promise<void>
  onSaveStatusInterval: (minutes: number) => Promise<void>
}

export function ManageTab({
  settings,
  sources,
  notificationChannels,
  activeWorkers,
  onSaveSettings,
  onCreateSources,
  onUpdateSource,
  onScanSource,
  onScanAllSources,
  onDeleteSource,
  onUpdateAccount,
  onChangeAdminPassword,
  onCreateNotification,
  onUpdateNotification,
  onDeleteNotification,
  onTestNotification,
  onSaveStatusInterval,
}: Props) {
  const [downloadDir, setDownloadDir] = useState('')
  const [maxConcurrent, setMaxConcurrent] = useState(2)
  const [defaultIntervalSec, setDefaultIntervalSec] = useState(300)
  const [minFreeMiB, setMinFreeMiB] = useState(512)
  const [savingSettings, setSavingSettings] = useState(false)
  const [settingsError, setSettingsError] = useState('')
  const [localModalOpen, setLocalModalOpen] = useState(false)

  const [remoteRootId, setRemoteRootId] = useState('')
  const [localAlias, setLocalAlias] = useState('')
  const [initialMode, setInitialMode] = useState<'download_existing' | 'monitor_new_only'>('download_existing')
  const [intervalSec, setIntervalSec] = useState(300)
  const [minFileSizeMiB, setMinFileSizeMiB] = useState(0)
  const [drafts, setDrafts] = useState<DraftSource[]>([])
  const [submittingSources, setSubmittingSources] = useState(false)
  const [sourceError, setSourceError] = useState('')
  const [remoteModalOpen, setRemoteModalOpen] = useState(false)

  const [editingIntervalId, setEditingIntervalId] = useState<string | null>(null)
  const [editingIntervalValue, setEditingIntervalValue] = useState(300)
  const [editingMinSizeId, setEditingMinSizeId] = useState<string | null>(null)
  const [editingMinSizeValue, setEditingMinSizeValue] = useState(0)
  const [scanAllLoading, setScanAllLoading] = useState(false)

  const [pikpakUser, setPikpakUser] = useState('')
  const [pikpakPass, setPikpakPass] = useState('')
  const [accountSaving, setAccountSaving] = useState(false)
  const [accountMessage, setAccountMessage] = useState('')
  const [adminCurrent, setAdminCurrent] = useState('')
  const [adminNew, setAdminNew] = useState('')
  const [adminConfirm, setAdminConfirm] = useState('')
  const [adminSaving, setAdminSaving] = useState(false)
  const [adminMessage, setAdminMessage] = useState('')

  const [notifyName, setNotifyName] = useState('')
  const [notifyType, setNotifyType] = useState<'feishu' | 'webhook'>('feishu')
  const [notifyURL, setNotifyURL] = useState('')
  const [notifySecret, setNotifySecret] = useState('')
  const [notifyEvents, setNotifyEvents] = useState<string[]>(NOTIFY_DEFAULT_EVENTS)
  const [notifySaving, setNotifySaving] = useState(false)
  const [notifyError, setNotifyError] = useState('')
  const [statusIntervalMinutes, setStatusIntervalMinutes] = useState(30)
  const [statusIntervalMessage, setStatusIntervalMessage] = useState('')

  useEffect(() => {
    if (!settings) return
    setDownloadDir(settings.download_dir)
    setMaxConcurrent(settings.max_concurrent_downloads)
    setDefaultIntervalSec(settings.default_scan_interval_sec)
    setMinFreeMiB(settings.min_free_space_mib)
    setIntervalSec(settings.default_scan_interval_sec)
    setStatusIntervalMinutes(settings.notification_status_interval_minutes ?? 30)
    setPikpakUser(settings.pikpak_username || '')
  }, [settings])

  async function handleSaveSettings(event: FormEvent) {
    event.preventDefault()
    setSettingsError('')
    setSavingSettings(true)
    try {
      await onSaveSettings({
        download_dir: downloadDir.trim(),
        max_concurrent_downloads: Number(maxConcurrent) || 2,
        min_free_space_mib: Number(minFreeMiB) || 0,
      })
    } catch (err) {
      setSettingsError(err instanceof Error ? err.message : '保存设置失败')
    } finally {
      setSavingSettings(false)
    }
  }

  async function handleCreateNotification(event: FormEvent) {
    event.preventDefault()
    setNotifyError('')
    setNotifySaving(true)
    try {
      await onCreateNotification({
        name: notifyName.trim(),
        type: notifyType,
        url: notifyURL.trim(),
        secret: notifySecret,
        events: notifyEvents,
        enabled: true,
      })
      setNotifyName('')
      setNotifyURL('')
      setNotifySecret('')
      setNotifyType('feishu')
      setNotifyEvents(NOTIFY_DEFAULT_EVENTS)
    } catch (err) {
      setNotifyError(err instanceof Error ? err.message : '添加通知渠道失败')
    } finally {
      setNotifySaving(false)
    }
  }

  function toggleNotifyEvent(key: string) {
    setNotifyEvents((prev) => (prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key]))
  }

  function uniqueAlias(baseName: string, taken: Set<string>): string {
    const clean = (baseName || 'PikPak').replace(/[<>:"/\\|?*]/g, '_').trim() || 'PikPak'
    if (!taken.has(clean.toLowerCase())) {
      taken.add(clean.toLowerCase())
      return clean
    }
    let idx = 2
    while (taken.has(`${clean}-${idx}`.toLowerCase())) {
      idx++
    }
    const candidate = `${clean}-${idx}`
    taken.add(candidate.toLowerCase())
    return candidate
  }

  function handlePickedRemoteFolders(folders: PickedRemoteFolder[]) {
    const taken = new Set<string>([
      ...sources.map((s) => s.local_alias.toLowerCase()),
      ...drafts.map((d) => d.localAlias.toLowerCase()),
    ])
    if (folders.length === 1 && drafts.length === 0) {
      const only = folders[0]
      setRemoteRootId(only.id)
      if (!localAlias.trim()) {
        setLocalAlias(uniqueAlias(only.name, taken))
      }
      return
    }
    const nextDrafts: DraftSource[] = folders.map((folder, index) => ({
      key: `${folder.id || 'root'}-${Date.now()}-${index}`,
      remoteRootId: folder.id,
      remotePathHint: folder.path || '/',
      localAlias: uniqueAlias(folder.name, taken),
      initialMode,
      scanIntervalSec: Number(intervalSec) || defaultIntervalSec || 300,
      minFileSizeMiB: Math.max(0, Number(minFileSizeMiB) || 0),
    }))
    setDrafts((prev) => [...prev, ...nextDrafts])
  }

  async function handleAddSingle(event: FormEvent) {
    event.preventDefault()
    setSourceError('')
    setSubmittingSources(true)
    try {
      await onCreateSources([
        {
          remote_root_id: remoteRootId.trim(),
          local_alias: localAlias.trim(),
          initial_mode: initialMode,
          scan_interval_sec: Number(intervalSec) || defaultIntervalSec || 300,
          min_file_size_bytes: Math.max(0, Number(minFileSizeMiB) || 0) * 1024 * 1024,
        },
      ])
      setRemoteRootId('')
      setLocalAlias('')
    } catch (err) {
      setSourceError(err instanceof Error ? err.message : '添加监控目录失败')
    } finally {
      setSubmittingSources(false)
    }
  }

  async function handleCommitDrafts() {
    if (drafts.length === 0) return
    setSourceError('')
    setSubmittingSources(true)
    try {
      await onCreateSources(
        drafts.map((d) => ({
          remote_root_id: d.remoteRootId,
          local_alias: d.localAlias.trim(),
          initial_mode: d.initialMode,
          scan_interval_sec: Number(d.scanIntervalSec) || 300,
          min_file_size_bytes: Math.max(0, Number(d.minFileSizeMiB) || 0) * 1024 * 1024,
        })),
      )
      setDrafts([])
    } catch (err) {
      setSourceError(err instanceof Error ? err.message : '批量添加失败')
    } finally {
      setSubmittingSources(false)
    }
  }

  const existingRootIds = new Set(sources.map((s) => s.remote_root_id))
  const effectiveDownloadRoot = settings?.download_dir || downloadDir

  return (
    <div className="space-y-6">
      <section className="rounded-2xl border border-slate-800 bg-slate-900/70 p-5 space-y-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-cyan-500/30 bg-cyan-500/15 text-cyan-400 shrink-0">
              <HardDrive className="h-5 w-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-slate-100">本地下载目录与并发设置</h2>
              <p className="text-xs text-slate-400">
                设置服务端本地保存根目录与下载并发参数。本地下载目录无需定时扫描，所有文件由下载任务直接交付到此目录。
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 text-xs text-slate-400">
            <span className="rounded-lg border border-slate-800 bg-slate-950 px-2.5 py-1">
              数据目录：<span className="font-mono text-slate-300">{settings?.data_dir || '—'}</span>
            </span>
            <span className="rounded-lg border border-slate-800 bg-slate-950 px-2.5 py-1">
              {settings?.pikpak_configured ? `PikPak 账号 ${settings.pikpak_username}` : 'PikPak 账号未配置'}
            </span>
          </div>
        </div>

        <form onSubmit={handleSaveSettings} className="space-y-4">
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-12">
            <label className="block space-y-1.5 lg:col-span-8">
              <span className="text-xs text-slate-400">服务端下载根目录（支持浏览器选择服务器路径）</span>
              <div className="flex gap-2">
                <input
                  type="text"
                  required
                  value={downloadDir}
                  onChange={(e) => setDownloadDir(e.target.value)}
                  placeholder="/downloads"
                  className="flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => setLocalModalOpen(true)}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-xs text-slate-200 hover:bg-slate-700 shrink-0 cursor-pointer"
                >
                  <FolderOpen className="h-3.5 w-3.5 text-cyan-400" />
                  浏览服务器目录
                </button>
              </div>
            </label>

            <label className="block space-y-1.5 lg:col-span-2">
              <span className="text-xs text-slate-400">最大并发下载 (1–16)</span>
              <input
                type="number"
                min={1}
                max={16}
                value={maxConcurrent}
                onChange={(e) => setMaxConcurrent(Number(e.target.value))}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 tabular-nums focus:border-blue-500 focus:outline-none"
              />
            </label>

            <label className="block space-y-1.5 lg:col-span-2">
              <span className="text-xs text-slate-400">最低保留空间（MiB）</span>
              <input
                type="number"
                min={0}
                value={minFreeMiB}
                onChange={(e) => setMinFreeMiB(Number(e.target.value))}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 tabular-nums focus:border-blue-500 focus:outline-none"
              />
            </label>
          </div>

          <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
            <p className="text-xs text-slate-400">
              切换下载目录时会检测卷原子交付能力，且不会搬动旧目录已有文件；若当前有进行中的下载（当前 {activeWorkers} 个），需先暂停。
            </p>
            <button
              type="submit"
              disabled={savingSettings}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
            >
              <Save className="h-3.5 w-3.5" />
              {savingSettings ? '保存并校验中…' : '保存设置'}
            </button>
          </div>
        </form>

        {settingsError && (
          <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
            {settingsError}
          </div>
        )}
      </section>

      <section className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-5 space-y-4">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-emerald-500/30 bg-emerald-500/15 text-emerald-400 shrink-0">
              <UserCog className="h-5 w-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-slate-100">PikPak 账号</h2>
              <p className="text-xs text-slate-400">修改后会重新验证登录；有活动下载任务时需先暂停。</p>
            </div>
          </div>
          <div className="space-y-3">
            <input
              type="text"
              value={pikpakUser}
              onChange={(e) => setPikpakUser(e.target.value)}
              placeholder="PikPak 用户名 / 邮箱"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <input
              type="password"
              value={pikpakPass}
              onChange={(e) => setPikpakPass(e.target.value)}
              placeholder="输入新密码才会更新"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <button
              type="button"
              disabled={accountSaving || !pikpakUser.trim() || !pikpakPass}
              onClick={async () => {
                setAccountMessage('')
                setAccountSaving(true)
                try {
                  await onUpdateAccount(pikpakUser.trim(), pikpakPass)
                  setPikpakPass('')
                  setAccountMessage('PikPak 账号已验证并保存')
                } catch (err) {
                  setAccountMessage(err instanceof Error ? err.message : '保存 PikPak 账号失败')
                } finally {
                  setAccountSaving(false)
                }
              }}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-40 cursor-pointer"
            >
              <Save className="h-3.5 w-3.5" />
              {accountSaving ? '验证中…' : '验证并保存账号'}
            </button>
            {accountMessage && <div className="text-xs text-slate-400">{accountMessage}</div>}
          </div>
        </div>

        <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-5 space-y-4">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-violet-500/30 bg-violet-500/15 text-violet-400 shrink-0">
              <ShieldCheck className="h-5 w-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-slate-100">管理员密码</h2>
              <p className="text-xs text-slate-400">用于登录当前 Web 管理面板，与 PikPak 密码无关。</p>
            </div>
          </div>
          <div className="space-y-3">
            <input
              type="password"
              value={adminCurrent}
              onChange={(e) => setAdminCurrent(e.target.value)}
              placeholder="当前管理员密码"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <input
              type="password"
              value={adminNew}
              onChange={(e) => setAdminNew(e.target.value)}
              placeholder="新密码（至少 8 位）"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <input
              type="password"
              value={adminConfirm}
              onChange={(e) => setAdminConfirm(e.target.value)}
              placeholder="再次输入新密码"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <button
              type="button"
              disabled={adminSaving || !adminCurrent || !adminNew}
              onClick={async () => {
                setAdminMessage('')
                if (adminNew !== adminConfirm) {
                  setAdminMessage('两次输入的新密码不一致')
                  return
                }
                setAdminSaving(true)
                try {
                  await onChangeAdminPassword(adminCurrent, adminNew)
                  setAdminCurrent('')
                  setAdminNew('')
                  setAdminConfirm('')
                  setAdminMessage('管理员密码已更新')
                } catch (err) {
                  setAdminMessage(err instanceof Error ? err.message : '修改密码失败')
                } finally {
                  setAdminSaving(false)
                }
              }}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-4 py-2 text-xs font-medium text-slate-200 hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
            >
              <ShieldCheck className="h-3.5 w-3.5" />
              {adminSaving ? '更新中…' : '修改管理员密码'}
            </button>
            {adminMessage && <div className="text-xs text-slate-400">{adminMessage}</div>}
          </div>
        </div>
      </section>

      <section className="rounded-2xl border border-slate-800 bg-slate-900/70 p-5 space-y-5">
        <div className="flex items-start gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-sky-500/30 bg-sky-500/15 text-sky-400 shrink-0">
            <Bell className="h-5 w-5" />
          </div>
          <div>
            <h2 className="text-base font-semibold text-slate-100">通知渠道</h2>
            <p className="text-xs text-slate-400">
              支持飞书群机器人和通用 Webhook；通知只包含事件数量与来源，不包含文件名，任务事件会短窗口汇总。
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-end gap-3 rounded-xl border border-slate-800/80 bg-slate-950/40 p-4">
          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">状态汇报间隔（分钟，0 关闭）</span>
            <input
              type="number"
              min={0}
              max={1440}
              value={statusIntervalMinutes}
              onChange={(e) => setStatusIntervalMinutes(Number(e.target.value))}
              className="w-32 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 tabular-nums focus:border-blue-500 focus:outline-none"
            />
          </label>
          <button
            type="button"
            onClick={async () => {
              setStatusIntervalMessage('')
              try {
                await onSaveStatusInterval(Math.max(0, Math.min(1440, Number(statusIntervalMinutes) || 0)))
                setStatusIntervalMessage('状态汇报间隔已保存')
              } catch (err) {
                setStatusIntervalMessage(err instanceof Error ? err.message : '保存状态汇报间隔失败')
              }
            }}
            className="rounded-lg border border-slate-700 bg-slate-800 px-3 py-2 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
          >
            保存间隔
          </button>
          <span className="text-xs text-slate-500">仅在存在队列/进行中任务时发送；内容只含数量、速度、剩余大小与 ETA。</span>
          {statusIntervalMessage && <span className="text-xs text-slate-400">{statusIntervalMessage}</span>}
        </div>

        <form onSubmit={handleCreateNotification} className="space-y-4 rounded-xl border border-slate-800/80 bg-slate-950/40 p-4">
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-4">
            <label className="block space-y-1.5">
              <span className="text-xs text-slate-400">渠道名称</span>
              <input
                type="text"
                required
                value={notifyName}
                onChange={(e) => setNotifyName(e.target.value)}
                placeholder="例如 运维飞书群"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
            </label>
            <label className="block space-y-1.5">
              <span className="text-xs text-slate-400">类型</span>
              <select
                value={notifyType}
                onChange={(e) => setNotifyType(e.target.value as 'feishu' | 'webhook')}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              >
                <option value="feishu">飞书群机器人</option>
                <option value="webhook">通用 Webhook</option>
              </select>
            </label>
            <label className="block space-y-1.5 md:col-span-2">
              <span className="text-xs text-slate-400">
                {notifyType === 'feishu' ? '飞书 Webhook URL' : 'Webhook URL'}
              </span>
              <input
                type="url"
                required
                value={notifyURL}
                onChange={(e) => setNotifyURL(e.target.value)}
                placeholder="https://..."
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
            </label>
            <label className="block space-y-1.5 md:col-span-2">
              <span className="text-xs text-slate-400">
                {notifyType === 'feishu' ? '飞书签名密钥（可选）' : 'Bearer Token（可选）'}
              </span>
              <input
                type="password"
                value={notifySecret}
                onChange={(e) => setNotifySecret(e.target.value)}
                placeholder={notifyType === 'feishu' ? '飞书机器人加签密钥' : '发送 Authorization: Bearer <token>'}
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
            </label>
          </div>

          <div className="space-y-2">
            <span className="text-xs text-slate-400">通知时机</span>
            <div className="flex flex-wrap gap-2">
              {NOTIFY_EVENTS.map((event) => {
                const checked = notifyEvents.includes(event.key)
                return (
                  <label
                    key={event.key}
                    className={`inline-flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-1.5 text-xs ${
                      checked
                        ? 'border-sky-500/50 bg-sky-500/15 text-sky-200'
                        : 'border-slate-700 bg-slate-900 text-slate-400'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleNotifyEvent(event.key)}
                      className="h-3.5 w-3.5 accent-sky-500"
                    />
                    {event.label}
                  </label>
                )
              })}
            </div>
          </div>

          <div className="flex items-center justify-between gap-3">
            <p className="text-xs text-slate-500">「加入下载队列」会按扫描批次汇总发送，避免大量文件逐条刷屏。</p>
            <button
              type="submit"
              disabled={notifySaving}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
            >
              <Plus className="h-3.5 w-3.5" />
              {notifySaving ? '添加中…' : '添加通知渠道'}
            </button>
          </div>
        </form>

        {notifyError && (
          <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
            {notifyError}
          </div>
        )}

        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {notificationChannels.length === 0 ? (
            <div className="col-span-full rounded-xl border border-dashed border-slate-800 py-8 text-center text-sm text-slate-500">
              尚未配置通知渠道
            </div>
          ) : (
            notificationChannels.map((channel) => (
              <div key={channel.id} className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <h3 className="truncate text-sm font-semibold text-slate-100">{channel.name}</h3>
                      <span className="rounded border border-slate-700 bg-slate-900 px-1.5 py-0.5 text-[10px] text-slate-400">
                        {channel.type === 'feishu' ? '飞书' : 'Webhook'}
                      </span>
                      {channel.has_secret && <span className="text-[10px] text-emerald-300">已加密凭据</span>}
                    </div>
                    <div className="mt-1 truncate font-mono text-[11px] text-slate-500">{channel.url_masked}</div>
                  </div>
                  <span
                    className={`rounded-full border px-2 py-0.5 text-[10px] ${
                      channel.enabled
                        ? 'border-emerald-500/30 bg-emerald-500/15 text-emerald-300'
                        : 'border-slate-700 bg-slate-800 text-slate-400'
                    }`}
                  >
                    {channel.enabled ? '已启用' : '已停用'}
                  </span>
                </div>
                <div className="flex flex-wrap gap-1">
                  {channel.events.map((event) => (
                    <span key={event} className="rounded bg-slate-800 px-1.5 py-0.5 text-[10px] text-slate-400">
                      {NOTIFY_EVENTS.find((item) => item.key === event)?.label || event}
                    </span>
                  ))}
                </div>
                <div className="flex items-center justify-end gap-2 border-t border-slate-800/80 pt-3">
                  <button
                    type="button"
                    onClick={async () => {
                      setNotifyError('')
                      try {
                        await onTestNotification(channel.id)
                      } catch (err) {
                        setNotifyError(err instanceof Error ? err.message : '测试通知发送失败')
                      }
                    }}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
                  >
                    <Send className="h-3.5 w-3.5" />
                    发送测试消息
                  </button>
                  <button
                    type="button"
                    onClick={async () => {
                      setNotifyError('')
                      try {
                        await onUpdateNotification(channel.id, { enabled: !channel.enabled })
                      } catch (err) {
                        setNotifyError(err instanceof Error ? err.message : '更新通知渠道失败')
                      }
                    }}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
                  >
                    <Power className="h-3.5 w-3.5" />
                    {channel.enabled ? '停用' : '启用'}
                  </button>
                  <button
                    type="button"
                    onClick={async () => {
                      if (!window.confirm(`确定删除通知渠道「${channel.name}」吗？`)) return
                      setNotifyError('')
                      try {
                        await onDeleteNotification(channel.id)
                      } catch (err) {
                        setNotifyError(err instanceof Error ? err.message : '删除通知渠道失败')
                      }
                    }}
                    className="inline-flex items-center gap-1 rounded-lg border border-rose-500/30 bg-rose-500/10 px-2.5 py-1.5 text-xs text-rose-300 hover:bg-rose-500/20 cursor-pointer"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    删除
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </section>

      <section className="rounded-2xl border border-slate-800 bg-slate-900/70 p-5 space-y-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-amber-500/30 bg-amber-500/15 text-amber-400 shrink-0">
              <FolderCog className="h-5 w-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold text-slate-100">PikPak 下载目录管理</h2>
              <p className="text-xs text-slate-400">
                只有远端 PikPak 目录需要定时轮询扫描（PikPak 无主动推送接口），用来检测新增文件并自动加入下载队列；远端删除默认只做标记，不删本地文件。
              </p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              disabled={scanAllLoading || sources.length === 0}
              onClick={async () => {
                setScanAllLoading(true)
                try {
                  await onScanAllSources()
                } finally {
                  setScanAllLoading(false)
                }
              }}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-800 px-3.5 py-2 text-xs font-medium text-slate-200 hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
            >
              <RefreshCw className={`h-4 w-4 text-emerald-400 ${scanAllLoading ? 'animate-spin' : ''}`} />
              {scanAllLoading ? '正在触发…' : '扫描全部已启用'}
            </button>
            <button
              type="button"
              onClick={() => setRemoteModalOpen(true)}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600/20 border border-blue-500/40 px-3.5 py-2 text-xs font-medium text-blue-200 hover:bg-blue-600/30 cursor-pointer"
            >
              <CheckSquare className="h-4 w-4 text-blue-400" />
              浏览并勾选 PikPak 目录
            </button>
          </div>
        </div>

        {drafts.length > 0 && (
          <div className="rounded-xl border border-blue-500/30 bg-blue-950/20 p-4 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-semibold text-blue-200">待添加的 PikPak 目录（{drafts.length} 个）</h3>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setDrafts([])}
                  className="rounded-lg border border-slate-700 px-2.5 py-1 text-xs text-slate-300 hover:bg-slate-800 cursor-pointer"
                >
                  清空
                </button>
                <button
                  type="button"
                  disabled={submittingSources}
                  onClick={() => void handleCommitDrafts()}
                  className="rounded-lg bg-blue-600 px-3 py-1 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
                >
                  {submittingSources ? '添加中…' : '确认批量添加并扫描'}
                </button>
              </div>
            </div>
            <div className="space-y-2">
              {drafts.map((item) => (
                <div key={item.key} className="grid grid-cols-1 gap-2 rounded-lg border border-slate-800 bg-slate-950/70 p-3 md:grid-cols-12 md:items-center">
                  <div className="md:col-span-4 min-w-0">
                    <div className="truncate text-xs font-medium text-slate-200">{item.remotePathHint}</div>
                    <div className="truncate font-mono text-[11px] text-slate-500">ID: {item.remoteRootId || '根目录'}</div>
                  </div>
                  <div className="md:col-span-3">
                    <input
                      type="text"
                      value={item.localAlias}
                      onChange={(e) =>
                        setDrafts((prev) => prev.map((d) => (d.key === item.key ? { ...d, localAlias: e.target.value } : d)))
                      }
                      placeholder="本地子目录名"
                      className="w-full rounded border border-slate-700 bg-slate-900 px-2.5 py-1.5 text-xs text-slate-100"
                    />
                  </div>
                  <div className="md:col-span-3">
                    <select
                      value={item.initialMode}
                      onChange={(e) =>
                        setDrafts((prev) =>
                          prev.map((d) =>
                            d.key === item.key
                              ? { ...d, initialMode: e.target.value as 'download_existing' | 'monitor_new_only' }
                              : d,
                          ),
                        )
                      }
                      className="w-full rounded border border-slate-700 bg-slate-900 px-2.5 py-1.5 text-xs text-slate-100"
                    >
                      <option value="download_existing">下载现有文件</option>
                      <option value="monitor_new_only">只监控以后新增</option>
                    </select>
                  </div>
                  <div className="md:col-span-2 flex items-center justify-end gap-2">
                    <input
                      type="number"
                      min={10}
                      value={item.scanIntervalSec}
                      onChange={(e) =>
                        setDrafts((prev) =>
                          prev.map((d) => (d.key === item.key ? { ...d, scanIntervalSec: Number(e.target.value) } : d)),
                        )
                      }
                      className="w-20 rounded border border-slate-700 bg-slate-900 px-2 py-1.5 text-xs text-slate-100 tabular-nums"
                    />
                    <button
                      type="button"
                      onClick={() => setDrafts((prev) => prev.filter((d) => d.key !== item.key))}
                      className="rounded p-1.5 text-slate-400 hover:bg-slate-800 hover:text-rose-300 cursor-pointer"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        <form onSubmit={handleAddSingle} className="grid grid-cols-1 gap-3 rounded-xl border border-slate-800/80 bg-slate-950/40 p-4 md:grid-cols-2 lg:grid-cols-6 lg:items-end">
          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">PikPak 目录 ID（留空为网盘根目录）</span>
            <input
              type="text"
              value={remoteRootId}
              onChange={(e) => setRemoteRootId(e.target.value)}
              placeholder="点右上角勾选或填 ID"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
          </label>

          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">本地保存子目录名</span>
            <input
              type="text"
              required
              value={localAlias}
              onChange={(e) => setLocalAlias(e.target.value)}
              placeholder="例如 Movies"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
          </label>

          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">首次扫描策略</span>
            <select
              value={initialMode}
              onChange={(e) => setInitialMode(e.target.value as 'download_existing' | 'monitor_new_only')}
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            >
              <option value="download_existing">下载现有文件</option>
              <option value="monitor_new_only">只监控以后新增</option>
            </select>
          </label>

          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">扫描周期（秒）</span>
            <input
              type="number"
              min={10}
              value={intervalSec}
              onChange={(e) => setIntervalSec(Number(e.target.value))}
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 tabular-nums focus:border-blue-500 focus:outline-none"
            />
          </label>

          <label className="block space-y-1.5">
            <span className="text-xs text-slate-400">最小文件大小（MiB，0 不限）</span>
            <input
              type="number"
              min={0}
              value={minFileSizeMiB}
              onChange={(e) => setMinFileSizeMiB(Number(e.target.value))}
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 tabular-nums focus:border-blue-500 focus:outline-none"
            />
          </label>

          <button
            type="submit"
            disabled={submittingSources}
            className="inline-flex items-center justify-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
          >
            <Plus className="h-4 w-4" />
            添加单个目录
          </button>
        </form>

        {sourceError && (
          <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
            {sourceError}
          </div>
        )}

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {sources.length === 0 ? (
            <div className="col-span-full rounded-2xl border border-dashed border-slate-800 py-12 text-center text-sm text-slate-500">
              尚未配置任何 PikPak 下载目录，点击「浏览并勾选 PikPak 目录」开始添加
            </div>
          ) : (
            sources.map((src) => {
              const isEditing = editingIntervalId === src.id
              const isEditingMin = editingMinSizeId === src.id
              const minSizeMiB = Math.round(src.min_file_size_bytes / (1024 * 1024))
              return (
                <div key={src.id} className="rounded-2xl border border-slate-800 bg-slate-950/60 p-4 flex flex-col justify-between gap-4">
                  <div className="space-y-2.5">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <h3 className="text-sm font-semibold text-slate-100 truncate">{src.local_alias}</h3>
                          <span
                            className={`rounded-full border px-2 py-0.5 text-[11px] font-medium ${
                              src.enabled
                                ? 'border-emerald-500/30 bg-emerald-500/15 text-emerald-300'
                                : 'border-slate-700 bg-slate-800 text-slate-400'
                            }`}
                          >
                            {src.enabled ? '监控中' : '已停用'}
                          </span>
                        </div>
                        <p className="mt-1 text-xs text-slate-400 font-mono truncate" title={src.remote_root_path}>
                          远端：{src.remote_root_path}（{src.remote_root_id || '根目录'}）
                        </p>
                        <p className="text-xs text-cyan-300/90 font-mono truncate" title={`${effectiveDownloadRoot}/${src.local_alias}`}>
                          本地：{effectiveDownloadRoot}/{src.local_alias}
                        </p>
                      </div>
                      <span className="rounded-lg border border-slate-800 bg-slate-900 px-2.5 py-1 text-[11px] text-slate-300 shrink-0">
                        {src.initial_mode === 'monitor_new_only' ? '只监控新增' : '下载存量'}
                      </span>
                    </div>

                    <div className="grid grid-cols-2 gap-2 text-xs text-slate-400">
                      <div>
                        上次扫描：<span className="text-slate-200">{formatTimestamp(src.last_scan_at)}</span>
                      </div>
                      <div>
                        下次扫描：<span className="text-slate-200">{src.enabled ? formatRelativeTime(src.next_scan_at) : '已暂停'}</span>
                      </div>
                      <div>
                        状态：
                        <span
                          className={
                            src.last_scan_status === 'FAILED'
                              ? 'text-rose-300'
                              : src.last_scan_status === 'SUCCEEDED'
                                ? 'text-emerald-300'
                                : 'text-slate-300'
                          }
                        >
                          {src.last_scan_status || '尚未运行'}
                        </span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span>周期：</span>
                        {isEditing ? (
                          <>
                            <input
                              type="number"
                              min={10}
                              value={editingIntervalValue}
                              onChange={(e) => setEditingIntervalValue(Number(e.target.value))}
                              className="w-16 rounded border border-slate-700 bg-slate-900 px-1.5 py-0.5 text-xs text-slate-100 tabular-nums"
                            />
                            <button
                              type="button"
                              onClick={async () => {
                                await onUpdateSource(src.id, { scan_interval_sec: Number(editingIntervalValue) || 300 })
                                setEditingIntervalId(null)
                              }}
                              className="rounded bg-blue-600 px-1.5 py-0.5 text-[11px] text-white cursor-pointer"
                            >
                              存
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingIntervalId(src.id)
                              setEditingIntervalValue(src.scan_interval_sec)
                            }}
                            className="text-slate-200 underline decoration-dotted hover:text-white cursor-pointer tabular-nums"
                          >
                            {src.scan_interval_sec}s
                          </button>
                        )}
                      </div>
                      <div className="col-span-2 flex items-center gap-1.5">
                        <span>最小文件大小：</span>
                        {isEditingMin ? (
                          <>
                            <input
                              type="number"
                              min={0}
                              value={editingMinSizeValue}
                              onChange={(e) => setEditingMinSizeValue(Number(e.target.value))}
                              className="w-20 rounded border border-slate-700 bg-slate-900 px-1.5 py-0.5 text-xs text-slate-100 tabular-nums"
                            />
                            <span className="text-slate-400">MiB</span>
                            <button
                              type="button"
                              onClick={async () => {
                                await onUpdateSource(src.id, {
                                  min_file_size_bytes: Math.max(0, Number(editingMinSizeValue) || 0) * 1024 * 1024,
                                })
                                setEditingMinSizeId(null)
                              }}
                              className="rounded bg-blue-600 px-1.5 py-0.5 text-[11px] text-white cursor-pointer"
                            >
                              存
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            onClick={() => {
                              setEditingMinSizeId(src.id)
                              setEditingMinSizeValue(minSizeMiB)
                            }}
                            className="text-slate-200 underline decoration-dotted hover:text-white cursor-pointer tabular-nums"
                          >
                            {src.min_file_size_bytes > 0 ? `${minSizeMiB} MiB` : '不限'}
                          </button>
                        )}
                      </div>
                    </div>

                    {src.last_scan_error && (
                      <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-200 break-all">
                        {src.last_scan_error}
                      </div>
                    )}
                  </div>

                  <div className="flex flex-wrap items-center justify-end gap-2 border-t border-slate-800/80 pt-3">
                    <button
                      onClick={() => {
                        if (window.confirm(`确定移除监控目录「${src.local_alias}」吗？\n\n这只会停止后续扫描并清理数据库索引，不会删除本地已经下载的文件。`)) {
                          void onDeleteSource(src.id)
                        }
                      }}
                      className="inline-flex items-center gap-1 rounded-lg border border-rose-500/30 bg-rose-500/10 px-2.5 py-1.5 text-xs text-rose-300 hover:bg-rose-500/20 cursor-pointer"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                      移除
                    </button>
                    <button
                      onClick={() => void onUpdateSource(src.id, { enabled: !src.enabled })}
                      className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
                    >
                      <Power className="h-3.5 w-3.5" />
                      {src.enabled ? '停用' : '启用'}
                    </button>
                    <button
                      disabled={src.last_scan_status === 'RUNNING' || !src.enabled}
                      title={!src.enabled ? '请先启用该来源再扫描' : '立即扫描'}
                      onClick={() => void onScanSource(src.id)}
                      className="inline-flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
                    >
                      <RefreshCw className={`h-3.5 w-3.5 ${src.last_scan_status === 'RUNNING' ? 'animate-spin' : ''}`} />
                      {!src.enabled ? '已停用' : src.last_scan_status === 'RUNNING' ? '扫描中' : '立即扫描'}
                    </button>
                  </div>
                </div>
              )
            })
          )}
        </div>
      </section>

      <LocalDirModal
        open={localModalOpen}
        initialPath={downloadDir || '/'}
        onClose={() => setLocalModalOpen(false)}
        onPick={(picked) => setDownloadDir(picked)}
      />

      <RemoteFolderModal
        open={remoteModalOpen}
        existingRootIds={existingRootIds}
        onClose={() => setRemoteModalOpen(false)}
        onPickMany={handlePickedRemoteFolders}
      />
    </div>
  )
}
