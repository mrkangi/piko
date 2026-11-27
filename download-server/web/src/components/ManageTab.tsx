import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import {
  ArrowUpCircle,
  Bell,
  CheckSquare,
  ChevronLeft,
  ChevronRight,
  Database,
  FolderCog,
  FolderOpen,
  HardDrive,
  Info,
  Network,
  Plus,
  Power,
  RefreshCw,
  RotateCcw,
  Save,
  ScrollText,
  Send,
  Server,
  ShieldCheck,
  Trash2,
  UserCog,
} from 'lucide-react'
import type { NotificationChannel, Source, SystemSettings, UpdateInfo } from '../types'
import { formatRelativeTime, formatTimestamp } from '../format'
import { LocalDirModal } from './LocalDirModal'
import { RemoteFolderModal, type PickedRemoteFolder } from './RemoteFolderModal'
import { Badge, Button, Card, CardHeader, EmptyState, Field, StatusDot, cn, inputClassName } from './ui'

export type ManageSection = 'storage' | 'sources' | 'account' | 'notifications' | 'system'

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
  { key: 'scan.failed', label: '来源扫描连续失败' },
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

const SECTIONS: Array<{ key: ManageSection; label: string; description: string; icon: typeof HardDrive }> = [
  { key: 'storage', label: '下载与存储', description: '目录、并发与分片参数', icon: HardDrive },
  { key: 'sources', label: '监控来源', description: 'PikPak 目录与扫描周期', icon: FolderCog },
  { key: 'account', label: '账号与代理', description: 'PikPak、管理员与网络', icon: UserCog },
  { key: 'notifications', label: '通知渠道', description: '飞书机器人与 Webhook', icon: Bell },
  { key: 'system', label: '系统与升级', description: '版本、日志与数据目录', icon: Server },
]

interface Props {
  settings: SystemSettings | null
  sources: Source[]
  notificationChannels: NotificationChannel[]
  activeWorkers: number
  section: ManageSection
  detailOpen: boolean
  updateInfo: UpdateInfo | null
  onSectionChange: (section: ManageSection) => void
  onDetailOpenChange: (open: boolean) => void
  onOpenUpdate: () => void
  onOpenLogs: () => void
  onSaveSettings: (patch: {
    download_dir?: string
    max_concurrent_downloads?: number
    segment_threads?: number
    segment_min_size_mib?: number
    default_scan_interval_sec?: number
    scan_failure_threshold?: number
    min_free_space_mib?: number
  }) => Promise<void>
  onSaveProxy: (proxyURL: string | null) => Promise<void>
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
  section,
  detailOpen,
  updateInfo,
  onSectionChange,
  onDetailOpenChange,
  onOpenUpdate,
  onOpenLogs,
  onSaveSettings,
  onSaveProxy,
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
  const [segmentThreads, setSegmentThreads] = useState(1)
  const [segmentMinMiB, setSegmentMinMiB] = useState(16)
  const [defaultIntervalSec, setDefaultIntervalSec] = useState(300)
  const [scanFailureThreshold, setScanFailureThreshold] = useState(3)
  const [scanPolicyDirty, setScanPolicyDirty] = useState(false)
  const [scanPolicySaving, setScanPolicySaving] = useState(false)
  const [scanPolicyError, setScanPolicyError] = useState('')
  const [minFreeMiB, setMinFreeMiB] = useState(512)
  const [savingSettings, setSavingSettings] = useState(false)
  const [settingsError, setSettingsError] = useState('')
  const [settingsDirty, setSettingsDirty] = useState(false)
  const [proxyInput, setProxyInput] = useState('')
  const [proxySaving, setProxySaving] = useState(false)
  const [proxyMessage, setProxyMessage] = useState('')
  const [localModalOpen, setLocalModalOpen] = useState(false)

  const [remoteRootId, setRemoteRootId] = useState('')
  const [localAlias, setLocalAlias] = useState('')
  const [initialMode, setInitialMode] = useState<'download_existing' | 'monitor_new_only'>('download_existing')
  const [intervalSec, setIntervalSec] = useState(300)
  const [sourceIntervalDirty, setSourceIntervalDirty] = useState(false)
  const [minFileSizeMiB, setMinFileSizeMiB] = useState(0)
  const [drafts, setDrafts] = useState<DraftSource[]>([])
  const [submittingSources, setSubmittingSources] = useState(false)
  const [sourceError, setSourceError] = useState('')
  const [remoteModalOpen, setRemoteModalOpen] = useState(false)
  const [addSourceOpen, setAddSourceOpen] = useState(false)

  const [editingIntervalId, setEditingIntervalId] = useState<string | null>(null)
  const [editingIntervalValue, setEditingIntervalValue] = useState(300)
  const [editingMinSizeId, setEditingMinSizeId] = useState<string | null>(null)
  const [editingMinSizeValue, setEditingMinSizeValue] = useState(0)
  const [scanAllLoading, setScanAllLoading] = useState(false)

  const [pikpakUser, setPikpakUser] = useState('')
  const [pikpakPass, setPikpakPass] = useState('')
  const [pikpakUserDirty, setPikpakUserDirty] = useState(false)
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
  const [statusIntervalDirty, setStatusIntervalDirty] = useState(false)
  const [statusIntervalMessage, setStatusIntervalMessage] = useState('')

  useEffect(() => {
    if (!settings) return
    if (!settingsDirty) {
      setDownloadDir(settings.download_dir)
      setMaxConcurrent(settings.max_concurrent_downloads)
      setSegmentThreads(settings.segment_threads)
      setSegmentMinMiB(settings.segment_min_size_mib)
      setMinFreeMiB(settings.min_free_space_mib)
    }
    if (!scanPolicyDirty) {
      setDefaultIntervalSec(settings.default_scan_interval_sec)
      setScanFailureThreshold(settings.scan_failure_threshold ?? 3)
    }
    if (!sourceIntervalDirty) {
      setIntervalSec(settings.default_scan_interval_sec)
    }
    if (!statusIntervalDirty) {
      setStatusIntervalMinutes(settings.notification_status_interval_minutes ?? 30)
    }
    if (!pikpakUserDirty) {
      setPikpakUser(settings.pikpak_username || '')
    }
  }, [settings, settingsDirty, scanPolicyDirty, sourceIntervalDirty, statusIntervalDirty, pikpakUserDirty])

  async function handleSaveSettings(event: FormEvent) {
    event.preventDefault()
    setSettingsError('')
    setSavingSettings(true)
    try {
      await onSaveSettings({
        download_dir: downloadDir.trim(),
        max_concurrent_downloads: Number(maxConcurrent) || 2,
        segment_threads: Number(segmentThreads) || 1,
        segment_min_size_mib: Number(segmentMinMiB) || 16,
        min_free_space_mib: Number(minFreeMiB) || 0,
      })
      setSettingsDirty(false)
    } catch (err) {
      setSettingsError(err instanceof Error ? err.message : '保存设置失败')
    } finally {
      setSavingSettings(false)
    }
  }

  function resetStorage() {
    if (!settings) return
    setDownloadDir(settings.download_dir)
    setMaxConcurrent(settings.max_concurrent_downloads)
    setSegmentThreads(settings.segment_threads)
    setSegmentMinMiB(settings.segment_min_size_mib)
    setMinFreeMiB(settings.min_free_space_mib)
    setSettingsDirty(false)
    setSettingsError('')
  }

  async function handleSaveScanPolicy(event: FormEvent) {
    event.preventDefault()
    setScanPolicyError('')
    setScanPolicySaving(true)
    try {
      await onSaveSettings({
        default_scan_interval_sec: Number(defaultIntervalSec) || 300,
        scan_failure_threshold: Number(scanFailureThreshold) || 3,
      })
      setScanPolicyDirty(false)
    } catch (err) {
      setScanPolicyError(err instanceof Error ? err.message : '保存扫描策略失败')
    } finally {
      setScanPolicySaving(false)
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
      ...sources.map((source) => source.local_alias.toLowerCase()),
      ...drafts.map((draft) => draft.localAlias.toLowerCase()),
    ])
    if (folders.length === 1 && drafts.length === 0) {
      const only = folders[0]
      setRemoteRootId(only.id)
      if (!localAlias.trim()) {
        setLocalAlias(uniqueAlias(only.name, taken))
      }
      setAddSourceOpen(true)
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
    setAddSourceOpen(true)
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
      setSourceIntervalDirty(false)
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
        drafts.map((draft) => ({
          remote_root_id: draft.remoteRootId,
          local_alias: draft.localAlias.trim(),
          initial_mode: draft.initialMode,
          scan_interval_sec: Number(draft.scanIntervalSec) || 300,
          min_file_size_bytes: Math.max(0, Number(draft.minFileSizeMiB) || 0) * 1024 * 1024,
        })),
      )
      setDrafts([])
    } catch (err) {
      setSourceError(err instanceof Error ? err.message : '批量添加失败')
    } finally {
      setSubmittingSources(false)
    }
  }

  const existingRootIds = new Set(sources.map((source) => source.remote_root_id))
  const effectiveDownloadRoot = settings?.download_dir || downloadDir

  return (
    <div className="grid gap-5 lg:grid-cols-[210px_minmax(0,1fr)] lg:gap-6">
      <aside className="hidden lg:block">
        <nav className="sticky top-20 space-y-1" aria-label="设置分区">
          {SECTIONS.map((item) => {
            const Icon = item.icon
            const active = section === item.key
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => onSectionChange(item.key)}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm transition-colors',
                  active ? 'bg-accent/12 text-ink' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
                )}
              >
                <Icon className={cn('h-4 w-4 shrink-0', active ? 'text-accent' : 'text-ink-3')} />
                {item.label}
              </button>
            )
          })}
        </nav>
      </aside>

      <div className="min-w-0">
        <div className={cn('space-y-2', detailOpen ? 'hidden' : 'lg:hidden')}>
          <div className="mb-1 px-1 text-[11px] text-ink-3">选择要修改的设置分区</div>
          {SECTIONS.map((item) => {
            const Icon = item.icon
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => {
                  onSectionChange(item.key)
                  onDetailOpenChange(true)
                }}
                className="flex w-full cursor-pointer items-center gap-3 rounded-xl border border-line bg-surface-1 px-3.5 py-3 text-left transition-colors hover:bg-surface-2/60"
              >
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2 text-ink-2">
                  <Icon className="h-4 w-4" />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium text-ink">{item.label}</div>
                  <div className="mt-0.5 truncate text-[11px] text-ink-3">{item.description}</div>
                </div>
                <ChevronRight className="h-4 w-4 shrink-0 text-ink-3" />
              </button>
            )
          })}
        </div>

        <div className={cn('min-w-0 space-y-4', detailOpen ? 'block' : 'hidden lg:block')}>
          {detailOpen && (
            <div className="lg:hidden">
              <Button variant="ghost" size="sm" onClick={() => onDetailOpenChange(false)} className="-ml-2">
                <ChevronLeft className="h-4 w-4" />
                返回设置
              </Button>
            </div>
          )}

          {section === 'storage' && (
            <div className="space-y-4">
              <Card>
                <CardHeader
                  title="下载与存储"
                  description="设置服务端本地保存根目录、并发与分片参数。修改只影响后续新任务，不会搬动已有文件。"
                  icon={<HardDrive className="h-4 w-4" />}
                />
                <form id="storage-form" onSubmit={handleSaveSettings} className="space-y-5 p-4 sm:p-5">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field
                      label="服务端下载根目录"
                      hint="支持直接输入服务器路径，或使用目录浏览器选择。"
                      className="sm:col-span-2"
                    >
                      <div className="flex flex-col gap-2 sm:flex-row">
                        <input
                          type="text"
                          required
                          value={downloadDir}
                          onChange={(event) => {
                            setDownloadDir(event.target.value)
                            setSettingsDirty(true)
                          }}
                          placeholder="/downloads"
                          className={cn(inputClassName, 'font-mono text-xs')}
                        />
                        <Button variant="secondary" onClick={() => setLocalModalOpen(true)} className="justify-center">
                          <FolderOpen className="h-3.5 w-3.5" />
                          浏览服务器目录
                        </Button>
                      </div>
                    </Field>

                    <Field label="最大并发下载" hint="允许同时运行的文件任务数，范围 1–16。">
                      <input
                        type="number"
                        min={1}
                        max={16}
                        value={maxConcurrent}
                        onChange={(event) => {
                          setMaxConcurrent(Number(event.target.value))
                          setSettingsDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>

                    <Field label="最低保留空间（MiB）" hint="低于该值时暂停派发新任务。">
                      <input
                        type="number"
                        min={0}
                        value={minFreeMiB}
                        onChange={(event) => {
                          setMinFreeMiB(Number(event.target.value))
                          setSettingsDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>

                    <Field label="单文件分片连接数" hint="1 为纯单流；2 以上且文件足够大时才启用 Range 分片并发。">
                      <input
                        type="number"
                        min={1}
                        max={16}
                        value={segmentThreads}
                        onChange={(event) => {
                          setSegmentThreads(Number(event.target.value))
                          setSettingsDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>

                    <Field label="最小分片大小（MiB）" hint="文件小于两倍该值时仍走单流，避免碎片连接。">
                      <input
                        type="number"
                        min={1}
                        max={4096}
                        value={segmentMinMiB}
                        onChange={(event) => {
                          setSegmentMinMiB(Number(event.target.value))
                          setSettingsDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>
                  </div>

                  <div className="rounded-lg border border-line bg-canvas/40 px-3 py-2.5 text-[11px] leading-5 text-ink-3">
                    分片遇到 429/503 会自动暂停整组、减半并发并退避重试；总连接数约为「最大并发下载 × 单文件分片连接数」。当前有 {activeWorkers} 个活动下载，切换下载目录前需先暂停。
                  </div>

                  {settingsError && (
                    <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{settingsError}</div>
                  )}

                  <div className="flex justify-end">
                    <Button type="submit" variant="primary" loading={savingSettings}>
                      <Save className="h-3.5 w-3.5" />
                      {savingSettings ? '保存并校验中…' : '保存设置'}
                    </Button>
                  </div>
                </form>
              </Card>

              {settingsDirty && (
                <div className="sticky bottom-[72px] z-20 flex items-center justify-between gap-3 rounded-xl border border-accent/30 bg-surface-2/95 px-3 py-2.5 shadow-xl backdrop-blur lg:bottom-4">
                  <span className="text-xs text-ink-2">有未保存的存储设置</span>
                  <div className="flex items-center gap-2">
                    <Button variant="ghost" size="sm" onClick={resetStorage}>
                      <RotateCcw className="h-3.5 w-3.5" />
                      重置
                    </Button>
                    <Button type="submit" form="storage-form" variant="primary" size="sm" loading={savingSettings}>
                      保存
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )}

          {section === 'sources' && (
            <div className="space-y-4">
              <Card>
                <CardHeader
                  title="扫描策略"
                  description="全局默认扫描周期与失败判定。单次网络抖动只会进入重试，达到阈值后才标记异常并发送通知。"
                  icon={<RefreshCw className="h-4 w-4" />}
                />
                <form onSubmit={handleSaveScanPolicy} className="space-y-4 p-4 sm:p-5">
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field label="默认扫描周期（秒）" hint="新建来源默认使用，已添加来源可在卡片内单独调整。">
                      <input
                        type="number"
                        min={10}
                        required
                        value={defaultIntervalSec}
                        onChange={(event) => {
                          setDefaultIntervalSec(Number(event.target.value))
                          setScanPolicyDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>
                    <Field label="连续失败判定阈值" hint="连续失败达到该次数后才标记来源异常；成功扫描一次会立即清零。">
                      <input
                        type="number"
                        min={1}
                        max={20}
                        required
                        value={scanFailureThreshold}
                        onChange={(event) => {
                          setScanFailureThreshold(Number(event.target.value))
                          setScanPolicyDirty(true)
                        }}
                        className={cn(inputClassName, 'tabular-nums')}
                      />
                    </Field>
                  </div>
                  {scanPolicyError && (
                    <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">{scanPolicyError}</div>
                  )}
                  <div className="flex items-center justify-between gap-3">
                    <p className="text-[11px] leading-4 text-ink-3">失败重试会按 30 秒、1 分钟、2 分钟逐步退避，最长 1 小时。</p>
                    <Button type="submit" variant="primary" loading={scanPolicySaving} disabled={!scanPolicyDirty}>
                      <Save className="h-3.5 w-3.5" />
                      {scanPolicySaving ? '保存中…' : '保存扫描策略'}
                    </Button>
                  </div>
                </form>
              </Card>

              <Card>
                <CardHeader
                  title="监控来源"
                  description="只有远端 PikPak 目录需要定时轮询扫描；远端删除默认只做标记，不删本地文件。"
                  icon={<FolderCog className="h-4 w-4" />}
                  actions={
                    <>
                      <Button
                        variant="secondary"
                        onClick={async () => {
                          setScanAllLoading(true)
                          try {
                            await onScanAllSources()
                          } finally {
                            setScanAllLoading(false)
                          }
                        }}
                        disabled={scanAllLoading || sources.length === 0}
                      >
                        <RefreshCw className={cn('h-3.5 w-3.5', scanAllLoading && 'animate-spin')} />
                        扫描全部
                      </Button>
                      <Button variant="secondary" onClick={() => setRemoteModalOpen(true)}>
                        <CheckSquare className="h-3.5 w-3.5" />
                        浏览并勾选
                      </Button>
                      <Button variant="primary" onClick={() => setAddSourceOpen((value) => !value)}>
                        <Plus className="h-3.5 w-3.5" />
                        添加目录
                      </Button>
                    </>
                  }
                />

                {drafts.length > 0 && (
                  <div className="border-b border-line bg-accent/5 p-3 sm:p-4">
                    <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2 text-xs font-medium text-accent">
                        <span>待添加目录</span>
                        <Badge tone="active">{drafts.length}</Badge>
                      </div>
                      <div className="flex items-center gap-2">
                        <Button variant="ghost" size="sm" onClick={() => setDrafts([])}>清空</Button>
                        <Button variant="primary" size="sm" onClick={() => void handleCommitDrafts()} loading={submittingSources}>
                          确认批量添加
                        </Button>
                      </div>
                    </div>
                    <div className="space-y-2">
                      {drafts.map((draft) => (
                        <div key={draft.key} className="grid gap-2 rounded-lg border border-line bg-canvas/50 p-2.5 md:grid-cols-12 md:items-center">
                          <div className="min-w-0 md:col-span-3">
                            <div className="truncate text-xs font-medium text-ink-2">{draft.remotePathHint}</div>
                            <div className="truncate font-mono text-[10px] text-ink-3">ID: {draft.remoteRootId || '根目录'}</div>
                          </div>
                          <input
                            type="text"
                            value={draft.localAlias}
                            onChange={(event) =>
                              setDrafts((prev) => prev.map((item) => (item.key === draft.key ? { ...item, localAlias: event.target.value } : item)))
                            }
                            placeholder="本地子目录名"
                            className={cn(inputClassName, 'h-8 py-0 text-xs md:col-span-3')}
                          />
                          <select
                            value={draft.initialMode}
                            onChange={(event) =>
                              setDrafts((prev) =>
                                prev.map((item) =>
                                  item.key === draft.key
                                    ? { ...item, initialMode: event.target.value as 'download_existing' | 'monitor_new_only' }
                                    : item,
                                ),
                              )
                            }
                            className={cn(inputClassName, 'h-8 py-0 text-xs md:col-span-3')}
                          >
                            <option value="download_existing">下载现有文件</option>
                            <option value="monitor_new_only">只监控以后新增</option>
                          </select>
                          <div className="flex items-center gap-2 md:col-span-3 md:justify-end">
                            <input
                              type="number"
                              min={10}
                              value={draft.scanIntervalSec}
                              onChange={(event) =>
                                setDrafts((prev) =>
                                  prev.map((item) => (item.key === draft.key ? { ...item, scanIntervalSec: Number(event.target.value) } : item)),
                                )
                              }
                              className={cn(inputClassName, 'h-8 w-20 py-0 text-xs tabular-nums')}
                            />
                            <span className="text-[10px] text-ink-3">秒</span>
                            <Button variant="ghost" size="icon-sm" onClick={() => setDrafts((prev) => prev.filter((item) => item.key !== draft.key))} aria-label="移除">
                              <Trash2 className="h-3.5 w-3.5" />
                            </Button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {addSourceOpen && (
                  <form onSubmit={handleAddSingle} className="grid gap-3 border-b border-line p-3 sm:p-4 md:grid-cols-2 lg:grid-cols-6 lg:items-end">
                    <Field label="PikPak 目录 ID" hint="留空为根目录" className="lg:col-span-1">
                      <input
                        type="text"
                        value={remoteRootId}
                        onChange={(event) => setRemoteRootId(event.target.value)}
                        placeholder="可留空"
                        className={cn(inputClassName, 'h-9 py-0 text-xs')}
                      />
                    </Field>
                    <Field label="本地保存子目录名">
                      <input
                        type="text"
                        required
                        value={localAlias}
                        onChange={(event) => setLocalAlias(event.target.value)}
                        placeholder="例如 Movies"
                        className={cn(inputClassName, 'h-9 py-0 text-xs')}
                      />
                    </Field>
                    <Field label="首次扫描策略">
                      <select
                        value={initialMode}
                        onChange={(event) => setInitialMode(event.target.value as 'download_existing' | 'monitor_new_only')}
                        className={cn(inputClassName, 'h-9 py-0 text-xs')}
                      >
                        <option value="download_existing">下载现有文件</option>
                        <option value="monitor_new_only">只监控以后新增</option>
                      </select>
                    </Field>
                    <Field label="扫描周期（秒）">
                      <input
                        type="number"
                        min={10}
                        value={intervalSec}
                        onChange={(event) => {
                          setIntervalSec(Number(event.target.value))
                          setSourceIntervalDirty(true)
                        }}
                        className={cn(inputClassName, 'h-9 py-0 text-xs tabular-nums')}
                      />
                    </Field>
                    <Field label="最小文件大小（MiB）" hint="0 表示不限">
                      <input
                        type="number"
                        min={0}
                        value={minFileSizeMiB}
                        onChange={(event) => setMinFileSizeMiB(Number(event.target.value))}
                        className={cn(inputClassName, 'h-9 py-0 text-xs tabular-nums')}
                      />
                    </Field>
                    <Button type="submit" variant="primary" loading={submittingSources} className="w-full">
                      <Plus className="h-4 w-4" />
                      添加
                    </Button>
                  </form>
                )}

                {sourceError && (
                  <div className="border-b border-line px-4 py-2 text-xs text-rose-300">{sourceError}</div>
                )}

                {sources.length === 0 ? (
                  <EmptyState
                    icon={<FolderCog className="h-5 w-5" />}
                    title="尚未配置监控目录"
                    description="点击「浏览并勾选」选择 PikPak 目录，扫描发现的新文件会自动加入下载队列。"
                    action={<Button variant="primary" size="sm" onClick={() => setRemoteModalOpen(true)}>选择目录</Button>}
                  />
                ) : (
                  <div className="grid gap-3 p-3 sm:p-4 xl:grid-cols-2">
                    {sources.map((source) => {
                      const editingInterval = editingIntervalId === source.id
                      const editingMinSize = editingMinSizeId === source.id
                      const minSizeMiB = Math.round(source.min_file_size_bytes / (1024 * 1024))
                      const failed = source.last_scan_status === 'FAILED'
                      const retrying = source.last_scan_status === 'RETRYING'
                      const failureThreshold = settings?.scan_failure_threshold ?? 3
                      return (
                        <article key={source.id} className="flex flex-col gap-3 rounded-xl border border-line bg-canvas/40 p-3.5">
                          <div className="flex items-start justify-between gap-2">
                            <div className="min-w-0">
                              <div className="flex flex-wrap items-center gap-1.5">
                                <h3 className="truncate text-sm font-semibold text-ink">{source.local_alias}</h3>
                                <Badge tone={!source.enabled ? 'neutral' : failed ? 'danger' : retrying ? 'wait' : 'success'}>
                                  <StatusDot tone={!source.enabled ? 'neutral' : failed ? 'danger' : retrying ? 'wait' : 'success'} />
                                  {!source.enabled ? '已停用' : failed ? '扫描异常' : retrying ? '重试中' : '监控中'}
                                </Badge>
                              </div>
                              <p className="mt-1 truncate font-mono text-[11px] text-ink-3" title={source.remote_root_path}>
                                远端 {source.remote_root_path}（{source.remote_root_id || '根目录'}）
                              </p>
                              <p className="truncate font-mono text-[11px] text-cyan-300/80" title={`${effectiveDownloadRoot}/${source.local_alias}`}>
                                本地 {effectiveDownloadRoot}/{source.local_alias}
                              </p>
                            </div>
                            <Badge tone="neutral">{source.initial_mode === 'monitor_new_only' ? '仅新增' : '含存量'}</Badge>
                          </div>

                          <div className="grid grid-cols-1 gap-x-3 gap-y-1.5 text-[10px] text-ink-3 sm:grid-cols-2 sm:text-[11px]">
                            <div>上次扫描 <span className="text-ink-2">{formatTimestamp(source.last_scan_at)}</span></div>
                            <div>下次 <span className="text-ink-2">{source.enabled ? formatRelativeTime(source.next_scan_at) : '已暂停'}</span></div>
                            <div>
                              状态{' '}
                              <span
                                className={
                                  failed
                                    ? 'text-rose-300'
                                    : retrying
                                      ? 'text-amber-300'
                                    : source.last_scan_status === 'SUCCEEDED'
                                      ? 'text-emerald-300'
                                      : 'text-ink-2'
                                }
                              >
                                {retrying
                                  ? `等待重试（${source.consecutive_failures}/${failureThreshold}）`
                                  : source.last_scan_status || '尚未运行'}
                              </span>
                            </div>
                            <div className="flex items-center gap-1">
                              周期{' '}
                              {editingInterval ? (
                                <>
                                  <input
                                    type="number"
                                    min={10}
                                    value={editingIntervalValue}
                                    onChange={(event) => setEditingIntervalValue(Number(event.target.value))}
                                    className={cn(inputClassName, 'h-6 w-16 px-1.5 py-0 text-[11px] tabular-nums')}
                                  />
                                  <Button
                                    variant="soft"
                                    size="icon-sm"
                                    onClick={async () => {
                                      await onUpdateSource(source.id, { scan_interval_sec: Number(editingIntervalValue) || 300 })
                                      setEditingIntervalId(null)
                                    }}
                                    aria-label="保存周期"
                                  >
                                    <Save className="h-3 w-3" />
                                  </Button>
                                </>
                              ) : (
                                <button
                                  type="button"
                                  onClick={() => {
                                    setEditingIntervalId(source.id)
                                    setEditingIntervalValue(source.scan_interval_sec)
                                  }}
                                  className="cursor-pointer text-ink-2 underline decoration-dotted underline-offset-2 hover:text-ink tabular-nums"
                                >
                                  {source.scan_interval_sec}s
                                </button>
                              )}
                            </div>
                            <div className="col-span-1 flex items-center gap-1 sm:col-span-2">
                              最小文件{' '}
                              {editingMinSize ? (
                                <>
                                  <input
                                    type="number"
                                    min={0}
                                    value={editingMinSizeValue}
                                    onChange={(event) => setEditingMinSizeValue(Number(event.target.value))}
                                    className={cn(inputClassName, 'h-6 w-20 px-1.5 py-0 text-[11px] tabular-nums')}
                                  />
                                  <span>MiB</span>
                                  <Button
                                    variant="soft"
                                    size="icon-sm"
                                    onClick={async () => {
                                      await onUpdateSource(source.id, {
                                        min_file_size_bytes: Math.max(0, Number(editingMinSizeValue) || 0) * 1024 * 1024,
                                      })
                                      setEditingMinSizeId(null)
                                    }}
                                    aria-label="保存最小文件大小"
                                  >
                                    <Save className="h-3 w-3" />
                                  </Button>
                                </>
                              ) : (
                                <button
                                  type="button"
                                  onClick={() => {
                                    setEditingMinSizeId(source.id)
                                    setEditingMinSizeValue(minSizeMiB)
                                  }}
                                  className="cursor-pointer text-ink-2 underline decoration-dotted underline-offset-2 hover:text-ink tabular-nums"
                                >
                                  {source.min_file_size_bytes > 0 ? `${minSizeMiB} MiB` : '不限'}
                                </button>
                              )}
                            </div>
                          </div>

                          {source.last_scan_error && (
                            <div
                              className={cn(
                                'rounded-lg border px-2.5 py-2 text-[11px] leading-4 break-words',
                                retrying
                                  ? 'border-amber-500/25 bg-amber-500/8 text-amber-100'
                                  : 'border-rose-500/25 bg-rose-500/8 text-rose-200',
                              )}
                            >
                              {retrying && (
                                <div className="mb-1 font-medium">
                                  临时错误（连续 {source.consecutive_failures}/{failureThreshold} 次，尚未确认异常）
                                </div>
                              )}
                              <div>{source.last_scan_error}</div>
                            </div>
                          )}

                          <div className="mt-auto flex flex-wrap items-center justify-end gap-1.5 border-t border-line pt-2.5">
                            <Button
                              variant="danger"
                              size="sm"
                              onClick={() => {
                                if (window.confirm(`确定移除监控目录「${source.local_alias}」吗？\n\n这只会停止后续扫描并清理数据库索引，不会删除本地已经下载的文件。`)) {
                                  void onDeleteSource(source.id)
                                }
                              }}
                            >
                              <Trash2 className="h-3.5 w-3.5" />
                              移除
                            </Button>
                            <Button variant="secondary" size="sm" onClick={() => void onUpdateSource(source.id, { enabled: !source.enabled })}>
                              <Power className="h-3.5 w-3.5" />
                              {source.enabled ? '停用' : '启用'}
                            </Button>
                            <Button
                              variant="soft"
                              size="sm"
                              disabled={source.last_scan_status === 'RUNNING' || !source.enabled}
                              title={!source.enabled ? '请先启用该来源再扫描' : '立即扫描'}
                              onClick={() => void onScanSource(source.id)}
                            >
                              <RefreshCw className={cn('h-3.5 w-3.5', source.last_scan_status === 'RUNNING' && 'animate-spin')} />
                              {!source.enabled ? '已停用' : source.last_scan_status === 'RUNNING' ? '扫描中' : '立即扫描'}
                            </Button>
                          </div>
                        </article>
                      )
                    })}
                  </div>
                )}
              </Card>
            </div>
          )}

          {section === 'account' && (
            <div className="space-y-4">
              <Card>
                <CardHeader
                  title="网络代理"
                  description="统一用于 PikPak API 与原始文件下载节点，支持 HTTP、HTTPS 与 SOCKS5。"
                  icon={<Network className="h-4 w-4" />}
                  actions={
                    <Badge tone={settings?.proxy_configured ? 'success' : 'neutral'}>
                      {settings?.proxy_configured ? `已启用 ${settings.proxy_url_masked}` : '当前直连'}
                    </Badge>
                  }
                />
                <div className="space-y-3 p-4 sm:p-5">
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <input
                      type="text"
                      value={proxyInput}
                      onChange={(event) => setProxyInput(event.target.value)}
                      placeholder="socks5://127.0.0.1:1080 或 http://user:pass@host:8080"
                      className={cn(inputClassName, 'min-w-0 flex-1 font-mono text-xs')}
                    />
                    <div className="grid grid-cols-2 gap-2 sm:flex">
                      <Button
                        variant="primary"
                        onClick={async () => {
                          setProxyMessage('')
                          setProxySaving(true)
                          try {
                            await onSaveProxy(proxyInput.trim())
                            setProxyInput('')
                            setProxyMessage('代理已验证并保存')
                          } catch (err) {
                            setProxyMessage(err instanceof Error ? err.message : '保存代理失败')
                          } finally {
                            setProxySaving(false)
                          }
                        }}
                        disabled={proxySaving || !proxyInput.trim()}
                        loading={proxySaving}
                      >
                        <Save className="h-3.5 w-3.5" />
                        验证并启用
                      </Button>
                      <Button
                        variant="secondary"
                        disabled={proxySaving || !settings?.proxy_configured}
                        onClick={async () => {
                          setProxyMessage('')
                          setProxySaving(true)
                          try {
                            await onSaveProxy(null)
                            setProxyInput('')
                            setProxyMessage('已关闭代理，恢复直连')
                          } catch (err) {
                            setProxyMessage(err instanceof Error ? err.message : '关闭代理失败')
                          } finally {
                            setProxySaving(false)
                          }
                        }}
                      >
                        关闭代理
                      </Button>
                    </div>
                  </div>
                  <p className="text-[11px] leading-5 text-ink-3">
                    地址中可包含用户名和密码，服务端会加密保存，页面只显示脱敏结果。修改后新请求立即生效，正在传输的连接会在下次重试时切换。
                  </p>
                  {proxyMessage && <div className="text-xs text-ink-2">{proxyMessage}</div>}
                </div>
              </Card>

              <div className="grid gap-4 xl:grid-cols-2">
                <Card>
                  <CardHeader
                    title="PikPak 账号"
                    description="修改后会重新验证登录；有活动下载任务时需先暂停。"
                    icon={<UserCog className="h-4 w-4" />}
                  />
                  <div className="space-y-3 p-4 sm:p-5">
                    <Field label="PikPak 用户名 / 邮箱">
                      <input
                        type="text"
                        value={pikpakUser}
                        onChange={(event) => {
                          setPikpakUser(event.target.value)
                          setPikpakUserDirty(true)
                        }}
                        placeholder="PikPak 用户名 / 邮箱"
                        className={inputClassName}
                      />
                    </Field>
                    <Field label="新密码" hint="留空不会修改当前密码。">
                      <input
                        type="password"
                        value={pikpakPass}
                        onChange={(event) => setPikpakPass(event.target.value)}
                        placeholder="输入新密码才会更新"
                        className={inputClassName}
                      />
                    </Field>
                    <Button
                      variant="primary"
                      disabled={accountSaving || !pikpakUser.trim() || !pikpakPass}
                      loading={accountSaving}
                      onClick={async () => {
                        setAccountMessage('')
                        setAccountSaving(true)
                        try {
                          await onUpdateAccount(pikpakUser.trim(), pikpakPass)
                          setPikpakUserDirty(false)
                          setPikpakPass('')
                          setAccountMessage('PikPak 账号已验证并保存')
                        } catch (err) {
                          setAccountMessage(err instanceof Error ? err.message : '保存 PikPak 账号失败')
                        } finally {
                          setAccountSaving(false)
                        }
                      }}
                      className="w-full sm:w-auto"
                    >
                      <Save className="h-3.5 w-3.5" />
                      {accountSaving ? '验证中…' : '验证并保存账号'}
                    </Button>
                    {accountMessage && <div className="text-xs text-ink-2">{accountMessage}</div>}
                  </div>
                </Card>

                <Card>
                  <CardHeader
                    title="管理员密码"
                    description="用于登录当前 Web 管理面板，与 PikPak 密码无关。"
                    icon={<ShieldCheck className="h-4 w-4" />}
                  />
                  <div className="space-y-3 p-4 sm:p-5">
                    <Field label="当前密码">
                      <input
                        type="password"
                        value={adminCurrent}
                        onChange={(event) => setAdminCurrent(event.target.value)}
                        placeholder="当前管理员密码"
                        className={inputClassName}
                      />
                    </Field>
                    <Field label="新密码">
                      <input
                        type="password"
                        value={adminNew}
                        onChange={(event) => setAdminNew(event.target.value)}
                        placeholder="新密码（至少 8 位）"
                        className={inputClassName}
                      />
                    </Field>
                    <Field label="确认新密码">
                      <input
                        type="password"
                        value={adminConfirm}
                        onChange={(event) => setAdminConfirm(event.target.value)}
                        placeholder="再次输入新密码"
                        className={inputClassName}
                      />
                    </Field>
                    <Button
                      variant="secondary"
                      disabled={adminSaving || !adminCurrent || !adminNew}
                      loading={adminSaving}
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
                      className="w-full sm:w-auto"
                    >
                      <ShieldCheck className="h-3.5 w-3.5" />
                      {adminSaving ? '更新中…' : '修改管理员密码'}
                    </Button>
                    {adminMessage && <div className="text-xs text-ink-2">{adminMessage}</div>}
                  </div>
                </Card>
              </div>
            </div>
          )}

          {section === 'notifications' && (
            <div className="space-y-4">
              <Card>
                <CardHeader
                  title="状态汇报"
                  description="仅在存在队列或进行中任务时发送，内容只含数量、速度、剩余大小与 ETA。"
                  icon={<Bell className="h-4 w-4" />}
                />
                <div className="flex flex-col gap-3 p-4 sm:flex-row sm:items-end sm:p-5">
                  <Field label="汇报间隔（分钟，0 关闭）" className="sm:w-44">
                    <input
                      type="number"
                      min={0}
                      max={1440}
                      value={statusIntervalMinutes}
                      onChange={(event) => {
                        setStatusIntervalMinutes(Number(event.target.value))
                        setStatusIntervalDirty(true)
                      }}
                      className={cn(inputClassName, 'tabular-nums')}
                    />
                  </Field>
                  <Button
                    variant="secondary"
                    onClick={async () => {
                      setStatusIntervalMessage('')
                      try {
                        await onSaveStatusInterval(Math.max(0, Math.min(1440, Number(statusIntervalMinutes) || 0)))
                        setStatusIntervalDirty(false)
                        setStatusIntervalMessage('状态汇报间隔已保存')
                      } catch (err) {
                        setStatusIntervalMessage(err instanceof Error ? err.message : '保存状态汇报间隔失败')
                      }
                    }}
                    className="w-full sm:w-auto"
                  >
                    <Save className="h-3.5 w-3.5" />
                    保存间隔
                  </Button>
                  {statusIntervalMessage && <span className="text-xs text-ink-3">{statusIntervalMessage}</span>}
                </div>
              </Card>

              <Card>
                <CardHeader
                  title="通知渠道"
                  description="支持飞书群机器人和通用 Webhook；通知只包含事件数量与来源，不包含文件名。"
                  icon={<Bell className="h-4 w-4" />}
                />
                <form onSubmit={handleCreateNotification} className="space-y-4 border-b border-line p-4 sm:p-5">
                  <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                    <Field label="渠道名称">
                      <input
                        type="text"
                        required
                        value={notifyName}
                        onChange={(event) => setNotifyName(event.target.value)}
                        placeholder="例如 运维飞书群"
                        className={inputClassName}
                      />
                    </Field>
                    <Field label="类型">
                      <select
                        value={notifyType}
                        onChange={(event) => setNotifyType(event.target.value as 'feishu' | 'webhook')}
                        className={inputClassName}
                      >
                        <option value="feishu">飞书群机器人</option>
                        <option value="webhook">通用 Webhook</option>
                      </select>
                    </Field>
                    <Field label={notifyType === 'feishu' ? '飞书 Webhook URL' : 'Webhook URL'} className="sm:col-span-2">
                      <input
                        type="url"
                        required
                        value={notifyURL}
                        onChange={(event) => setNotifyURL(event.target.value)}
                        placeholder="https://..."
                        className={cn(inputClassName, 'font-mono text-xs')}
                      />
                    </Field>
                    <Field
                      label={notifyType === 'feishu' ? '飞书签名密钥（可选）' : 'Bearer Token（可选）'}
                      className="sm:col-span-2"
                    >
                      <input
                        type="password"
                        value={notifySecret}
                        onChange={(event) => setNotifySecret(event.target.value)}
                        placeholder={notifyType === 'feishu' ? '飞书机器人加签密钥' : '发送 Authorization: Bearer <token>'}
                        className={inputClassName}
                      />
                    </Field>
                  </div>

                  <div className="space-y-2">
                    <span className="block text-xs font-medium text-ink-2">通知时机</span>
                    <div className="flex flex-wrap gap-1.5">
                      {NOTIFY_EVENTS.map((event) => {
                        const checked = notifyEvents.includes(event.key)
                        return (
                          <label
                            key={event.key}
                            className={cn(
                              'inline-flex cursor-pointer items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] transition-colors',
                              checked
                                ? 'border-accent/40 bg-accent/12 text-accent'
                                : 'border-line bg-canvas/40 text-ink-3 hover:text-ink-2',
                            )}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              onChange={() => toggleNotifyEvent(event.key)}
                              className="h-3 w-3 accent-[var(--color-accent)]"
                            />
                            {event.label}
                          </label>
                        )
                      })}
                    </div>
                  </div>

                  <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-[11px] text-ink-3">「加入下载队列」会按扫描批次汇总发送，避免大量文件逐条刷屏。</p>
                    <Button type="submit" variant="primary" loading={notifySaving} className="w-full sm:w-auto">
                      <Plus className="h-3.5 w-3.5" />
                      {notifySaving ? '添加中…' : '添加通知渠道'}
                    </Button>
                  </div>
                </form>

                {notifyError && (
                  <div className="border-b border-line px-4 py-2 text-xs text-rose-300">{notifyError}</div>
                )}

                {notificationChannels.length === 0 ? (
                  <EmptyState icon={<Bell className="h-5 w-5" />} title="尚未配置通知渠道" description="添加飞书机器人或 Webhook 后，任务结果会推送到对应群组。" />
                ) : (
                  <div className="grid gap-3 p-3 sm:p-4 xl:grid-cols-2">
                    {notificationChannels.map((channel) => (
                      <div key={channel.id} className="rounded-xl border border-line bg-canvas/40 p-3.5">
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="flex flex-wrap items-center gap-1.5">
                              <h3 className="truncate text-sm font-semibold text-ink">{channel.name}</h3>
                              <Badge tone="neutral">{channel.type === 'feishu' ? '飞书' : 'Webhook'}</Badge>
                              {channel.has_secret && <Badge tone="success">已加密凭据</Badge>}
                            </div>
                            <div className="mt-1 truncate font-mono text-[11px] text-ink-3">{channel.url_masked}</div>
                          </div>
                          <Badge tone={channel.enabled ? 'success' : 'neutral'}>{channel.enabled ? '已启用' : '已停用'}</Badge>
                        </div>
                        <div className="mt-2 flex flex-wrap gap-1">
                          {channel.events.map((event) => (
                            <span key={event} className="rounded border border-line bg-surface-2 px-1.5 py-0.5 text-[10px] text-ink-3">
                              {NOTIFY_EVENTS.find((item) => item.key === event)?.label || event}
                            </span>
                          ))}
                        </div>
                        <div className="mt-3 flex flex-wrap items-center justify-end gap-1.5 border-t border-line pt-2.5">
                          <Button
                            variant="secondary"
                            size="sm"
                            onClick={async () => {
                              setNotifyError('')
                              try {
                                await onTestNotification(channel.id)
                              } catch (err) {
                                setNotifyError(err instanceof Error ? err.message : '测试通知发送失败')
                              }
                            }}
                          >
                            <Send className="h-3.5 w-3.5" />
                            测试
                          </Button>
                          <Button
                            variant="secondary"
                            size="sm"
                            onClick={async () => {
                              setNotifyError('')
                              try {
                                await onUpdateNotification(channel.id, { enabled: !channel.enabled })
                              } catch (err) {
                                setNotifyError(err instanceof Error ? err.message : '更新通知渠道失败')
                              }
                            }}
                          >
                            <Power className="h-3.5 w-3.5" />
                            {channel.enabled ? '停用' : '启用'}
                          </Button>
                          <Button
                            variant="danger"
                            size="sm"
                            onClick={async () => {
                              if (!window.confirm(`确定删除通知渠道「${channel.name}」吗？`)) return
                              setNotifyError('')
                              try {
                                await onDeleteNotification(channel.id)
                              } catch (err) {
                                setNotifyError(err instanceof Error ? err.message : '删除通知渠道失败')
                              }
                            }}
                          >
                            <Trash2 className="h-3.5 w-3.5" />
                            删除
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </Card>
            </div>
          )}

          {section === 'system' && (
            <div className="space-y-4">
              <Card>
                <CardHeader
                  title="版本与在线升级"
                  description="升级包会先校验 SHA256，再替换当前程序并自动重启；进行中的任务会在重启后恢复。"
                  icon={<ArrowUpCircle className="h-4 w-4" />}
                  actions={
                    <Badge tone={updateInfo?.available ? 'active' : 'neutral'}>
                      {updateInfo?.available ? `可升级到 ${updateInfo.latest_version || '新版本'}` : '已是最新'}
                    </Badge>
                  }
                />
                <div className="space-y-3 p-4 sm:p-5">
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                    <InfoTile label="当前版本" value={updateInfo?.current_version || '—'} mono />
                    <InfoTile label="更新通道" value={channelLabel(updateInfo?.channel)} />
                    <InfoTile label="最新版本" value={updateInfo?.latest_version || '未检查'} mono />
                    <InfoTile label="构建时间" value={formatBuildTime(updateInfo?.build_time)} />
                  </div>
                  {updateInfo?.check_error && (
                    <div className="rounded-lg border border-amber-500/25 bg-amber-500/8 px-3 py-2 text-[11px] text-amber-200">{updateInfo.check_error}</div>
                  )}
                  <div className="flex flex-wrap items-center gap-2">
                    <Button variant="primary" onClick={onOpenUpdate}>
                      <ArrowUpCircle className="h-3.5 w-3.5" />
                      检查更新并升级
                    </Button>
                    {updateInfo?.release_url && (
                      <a
                        href={updateInfo.release_url}
                        target="_blank"
                        rel="noreferrer"
                        className="text-[11px] text-accent hover:underline"
                      >
                        查看发布页
                      </a>
                    )}
                  </div>
                </div>
              </Card>

              <div className="grid gap-4 xl:grid-cols-2">
                <Card>
                  <CardHeader title="运行日志" description="日志只保留在内存中，服务重启后清空。" icon={<ScrollText className="h-4 w-4" />} />
                  <div className="space-y-3 p-4 sm:p-5">
                    <p className="text-[11px] leading-5 text-ink-3">
                      支持按警告、错误筛选并导出当前窗口。日志已对邮箱、路径和令牌做脱敏处理。
                    </p>
                    <Button variant="secondary" onClick={onOpenLogs}>
                      <ScrollText className="h-3.5 w-3.5" />
                      打开运行日志
                    </Button>
                  </div>
                </Card>

                <Card>
                  <CardHeader title="数据与路径" description="数据目录固定，下载目录可在「下载与存储」中修改。" icon={<Database className="h-4 w-4" />} />
                  <div className="space-y-2.5 p-4 sm:p-5">
                    <div>
                      <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-ink-3">数据目录</div>
                      <div className="rounded-lg border border-line bg-canvas/50 px-2.5 py-2 font-mono text-[11px] text-ink-2 break-all">
                        {settings?.data_dir || '—'}
                      </div>
                    </div>
                    <div>
                      <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-ink-3">下载根目录</div>
                      <div className="rounded-lg border border-line bg-canvas/50 px-2.5 py-2 font-mono text-[11px] text-ink-2 break-all">
                        {settings?.download_dir || '—'}
                      </div>
                    </div>
                    <p className="text-[11px] leading-5 text-ink-3">
                      数据目录保存 SQLite、密钥、会话与服务锁；备份时需同时保留数据目录与已交付文件。
                    </p>
                  </div>
                </Card>
              </div>

              <Card>
                <CardHeader title="关于" icon={<Info className="h-4 w-4" />} />
                <div className="space-y-2 p-4 text-[11px] leading-5 text-ink-3 sm:p-5">
                  <p>Piko Download Server 是部署在 NAS 或服务器上的独立下载服务，浏览器只负责配置与查看，文件不经过浏览器。</p>
                  <p>当前账号：<span className="text-ink-2">{settings?.pikpak_configured ? settings.pikpak_username : '未配置 PikPak 账号'}</span></p>
                  {updateInfo?.repo && (
                    <a
                      href={`https://github.com/${updateInfo.repo}`}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-accent hover:underline"
                    >
                      项目仓库 {updateInfo.repo}
                    </a>
                  )}
                </div>
              </Card>
            </div>
          )}
        </div>
      </div>

      <LocalDirModal
        open={localModalOpen}
        initialPath={downloadDir || '/'}
        onClose={() => setLocalModalOpen(false)}
        onPick={(picked) => {
          setDownloadDir(picked)
          setSettingsDirty(true)
        }}
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

function InfoTile({ label, value, mono = false }: { label: string; value: ReactNode; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-line bg-canvas/40 px-3 py-2.5">
      <div className="text-[10px] font-medium uppercase tracking-wide text-ink-3">{label}</div>
      <div className={cn('mt-1 break-words text-[11px] leading-4 text-ink-2', mono && 'font-mono')}>{value}</div>
    </div>
  )
}

function channelLabel(channel: UpdateInfo['channel'] | undefined): string {
  if (channel === 'test') return '测试版'
  if (channel === 'dev') return '开发构建'
  return '稳定版'
}

function formatBuildTime(raw?: string): string {
  if (!raw) return '—'
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return raw
  return date.toLocaleString('zh-CN', { hour12: false })
}
