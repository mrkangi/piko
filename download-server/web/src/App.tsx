import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Activity,
  CloudDownload,
  Eye,
  EyeOff,
  HardDrive,
  Layers,
  LogOut,
  RefreshCw,
  ScrollText,
  Settings,
  FileCheck2,
} from 'lucide-react'
import * as api from './api'
import { formatBytes, formatSpeed } from './format'
import type { AuthUser, FileView, NotificationChannel, SetupStatus, Source, SystemSettings, SystemStats, TaskView } from './types'
import { LoginView } from './components/LoginView'
import { SetupView } from './components/SetupView'
import { SpeedSparkline } from './components/SpeedSparkline'
import { TasksTab } from './components/TasksTab'
import { ManageTab } from './components/ManageTab'
import { FilesTab } from './components/FilesTab'
import { LogsTab } from './components/LogsTab'
import { TaskDetailDrawer } from './components/TaskDetailDrawer'
import { useDebouncedValue } from './useDebouncedValue'

type TabKey = 'tasks' | 'files' | 'logs' | 'manage'

interface RefreshOptions {
  stats: boolean
  settings: boolean
  sources: boolean
  tasks: boolean
  files: boolean
  notifications: boolean
}

function refreshScope(partial: Partial<RefreshOptions>): RefreshOptions {
  return {
    stats: false,
    settings: false,
    sources: false,
    tasks: false,
    files: false,
    notifications: false,
    ...partial,
  }
}

const REFRESH_ALL: RefreshOptions = refreshScope({
  stats: true,
  settings: true,
  sources: true,
  tasks: true,
  files: true,
  notifications: true,
})

const FILENAME_VISIBILITY_KEY = 'piko.download.hideFilenames'
const TASK_PAGE_SIZE = 50
const FILE_PAGE_SIZE = 50

function tabFromPath(pathname: string): TabKey {
  if (pathname.startsWith('/manage') || pathname.startsWith('/sources')) return 'manage'
  if (pathname.startsWith('/files')) return 'files'
  if (pathname.startsWith('/logs')) return 'logs'
  return 'tasks'
}

function readHideFilenames(): boolean {
  try {
    const stored = window.localStorage.getItem(FILENAME_VISIBILITY_KEY)
    return stored === null ? true : stored === '1'
  } catch {
    return true
  }
}

export function App() {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [setupStatus, setSetupStatus] = useState<SetupStatus | null>(null)
  const [checkingAuth, setCheckingAuth] = useState(true)
  const [activeTab, setActiveTab] = useState<TabKey>(() => tabFromPath(window.location.pathname))
  const [sseState, setSseState] = useState<'connecting' | 'connected' | 'reconnecting'>('connecting')

  const [stats, setStats] = useState<SystemStats | null>(null)
  const [settings, setSettings] = useState<SystemSettings | null>(null)
  const [speedHistory, setSpeedHistory] = useState<number[]>([0, 0])
  const [sources, setSources] = useState<Source[]>([])
  const [notificationChannels, setNotificationChannels] = useState<NotificationChannel[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [taskTotal, setTaskTotal] = useState(0)
  const [taskOffset, setTaskOffset] = useState(0)
  const [files, setFiles] = useState<FileView[]>([])
  const [fileTotal, setFileTotal] = useState(0)
  const [fileOffset, setFileOffset] = useState(0)

  const [taskStateFilter, setTaskStateFilter] = useState('')
  const [taskSourceFilter, setTaskSourceFilter] = useState('')
  const [taskSearch, setTaskSearch] = useState('')
  const [hideFilenames, setHideFilenames] = useState(readHideFilenames)
  const debouncedTaskSearch = useDebouncedValue(taskSearch, 300)

  const [fileRemoteState, setFileRemoteState] = useState('')
  const [fileSourceFilter, setFileSourceFilter] = useState('')
  const [fileSearch, setFileSearch] = useState('')
  const debouncedFileSearch = useDebouncedValue(fileSearch, 300)

  const [inspectedTask, setInspectedTask] = useState<TaskView | null>(null)
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const refreshTimerRef = useRef<number | null>(null)
  const pendingRefreshRef = useRef<RefreshOptions>(refreshScope({}))
  const taskRequestSeqRef = useRef(0)
  const fileRequestSeqRef = useRef(0)

  const notify = useCallback((kind: 'ok' | 'error', text: string) => {
    setToast({ kind, text })
  }, [])

  useEffect(() => {
    if (!toast) return
    const id = window.setTimeout(() => setToast(null), 3500)
    return () => window.clearTimeout(id)
  }, [toast])

  useEffect(() => {
    try {
      window.localStorage.setItem(FILENAME_VISIBILITY_KEY, hideFilenames ? '1' : '0')
    } catch {
      // Private browsing may reject storage writes; the toggle still works for this page.
    }
  }, [hideFilenames])

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === FILENAME_VISIBILITY_KEY) {
        setHideFilenames(event.newValue === null ? true : event.newValue === '1')
      }
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const refreshAll = useCallback(async (options: RefreshOptions = REFRESH_ALL) => {
    const taskRequestSeq = options.tasks ? ++taskRequestSeqRef.current : 0
    const fileRequestSeq = options.files ? ++fileRequestSeqRef.current : 0
    try {
      const [nextStats, nextSettings, nextSources, nextTasks, nextFiles, nextNotifications] = await Promise.all([
        options.stats ? api.getStats() : Promise.resolve(null),
        options.settings ? api.getSettings() : Promise.resolve(null),
        options.sources ? api.listSources() : Promise.resolve(null),
        options.tasks
          ? api.listTasks({
              state: taskStateFilter,
              source_id: taskSourceFilter,
              q: debouncedTaskSearch,
              limit: TASK_PAGE_SIZE,
              offset: taskOffset,
            })
          : Promise.resolve(null),
        options.files
          ? api.listFiles({
              remote_state: fileRemoteState,
              source_id: fileSourceFilter,
              q: debouncedFileSearch,
              limit: FILE_PAGE_SIZE,
              offset: fileOffset,
            })
          : Promise.resolve(null),
        options.notifications ? api.listNotifications() : Promise.resolve(null),
      ])
      if (nextStats) {
        setStats(nextStats)
        setSpeedHistory((prev) => [...prev.slice(-24), nextStats.total_speed_bytes])
      }
      if (nextSettings) setSettings(nextSettings)
      if (nextSources) setSources(nextSources.sources || [])
      if (nextNotifications) setNotificationChannels(nextNotifications.channels || [])
      if (nextTasks && taskRequestSeq === taskRequestSeqRef.current) {
        const nextTaskList = nextTasks.tasks || []
        const nextTaskTotal = nextTasks.total || 0
        setTasks(nextTaskList)
        setTaskTotal(nextTaskTotal)
        if (taskOffset > 0 && nextTaskList.length === 0 && nextTaskTotal > 0) {
          setTaskOffset(Math.floor((nextTaskTotal - 1) / TASK_PAGE_SIZE) * TASK_PAGE_SIZE)
        }
        setInspectedTask((prev) => (prev ? nextTaskList.find((t) => t.id === prev.id) || null : null))
      }
      if (nextFiles && fileRequestSeq === fileRequestSeqRef.current) {
        const nextFileList = nextFiles.files || []
        const nextFileTotal = nextFiles.total || 0
        setFiles(nextFileList)
        setFileTotal(nextFileTotal)
        if (fileOffset > 0 && nextFileList.length === 0 && nextFileTotal > 0) {
          setFileOffset(Math.floor((nextFileTotal - 1) / FILE_PAGE_SIZE) * FILE_PAGE_SIZE)
        }
      }
    } catch (err) {
      if (err instanceof Error && err.message !== '请先登录') {
        notify('error', err.message)
      }
    }
  }, [
    taskStateFilter,
    taskSourceFilter,
    debouncedTaskSearch,
    taskOffset,
    fileRemoteState,
    fileSourceFilter,
    debouncedFileSearch,
    fileOffset,
    notify,
  ])

  const scheduleRefresh = useCallback((scope: RefreshOptions) => {
    const pending = pendingRefreshRef.current
    pendingRefreshRef.current = {
      stats: pending.stats || scope.stats,
      settings: pending.settings || scope.settings,
      sources: pending.sources || scope.sources,
      tasks: pending.tasks || scope.tasks,
      files: pending.files || scope.files,
      notifications: pending.notifications || scope.notifications,
    }
    if (refreshTimerRef.current !== null) return
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null
      const next = pendingRefreshRef.current
      pendingRefreshRef.current = refreshScope({})
      void refreshAll(next)
    }, 250)
  }, [refreshAll])

  useEffect(() => {
    api.setUnauthorizedHandler(() => {
      setUser(null)
    })
    api
      .getSetupStatus()
      .then(async (status) => {
        setSetupStatus(status)
        if (status.needs_setup) return
        try {
          const u = await api.me()
          setUser(u)
        } catch {
          setUser(null)
        }
      })
      .catch(() => setUser(null))
      .finally(() => setCheckingAuth(false))
  }, [])

  useEffect(() => {
    const onPopState = () => setActiveTab(tabFromPath(window.location.pathname))
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  useEffect(() => {
    if (!user) return
    void refreshAll()
  }, [user, refreshAll])

  useEffect(() => {
    if (!user) return
    setSseState('connecting')
    const es = new EventSource(api.apiURL('/api/events'), { withCredentials: true })
    es.addEventListener('ready', () => {
      setSseState('connected')
      void refreshAll()
    })
    const handleTaskProgress = () => scheduleRefresh(refreshScope({ stats: true, tasks: true }))
    const handleTaskOutcome = () => scheduleRefresh(refreshScope({ stats: true, tasks: true, files: true }))
    const handleCatalog = () => scheduleRefresh(refreshScope({ stats: true, sources: true, tasks: true, files: true }))
    const handleSettings = () => scheduleRefresh(refreshScope({ stats: true, settings: true }))
    const handleStats = () => scheduleRefresh(refreshScope({ stats: true }))
    ;['task.updated'].forEach((name) => es.addEventListener(name, handleTaskProgress))
    ;['task.started', 'task.completed', 'task.failed', 'task.conflict', 'task.retry', 'tasks.queued'].forEach((name) => {
      es.addEventListener(name, handleTaskOutcome)
    })
    ;['source.updated', 'scan.started', 'scan.finished', 'scan.failed'].forEach((name) => {
      es.addEventListener(name, handleCatalog)
    })
    es.addEventListener('settings.updated', handleSettings)
    ;['system.stats', 'system.low_disk', 'system.disk_ok', 'status.report'].forEach((name) => {
      es.addEventListener(name, handleStats)
    })
    es.onerror = () => setSseState('reconnecting')
    return () => {
      es.close()
      if (refreshTimerRef.current !== null) {
        window.clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
      pendingRefreshRef.current = refreshScope({})
    }
  }, [user, refreshAll, scheduleRefresh])

  function switchTab(tab: TabKey) {
    setActiveTab(tab)
    const targetPath = `/${tab}`
    if (window.location.pathname !== targetPath) {
      window.history.pushState(null, '', targetPath)
    }
  }

  function changeTaskStateFilter(value: string) {
    setTaskStateFilter(value)
    setTaskOffset(0)
  }

  function changeTaskSourceFilter(value: string) {
    setTaskSourceFilter(value)
    setTaskOffset(0)
  }

  function changeTaskSearch(value: string) {
    setTaskSearch(value)
    setTaskOffset(0)
  }

  function changeFileRemoteState(value: string) {
    setFileRemoteState(value)
    setFileOffset(0)
  }

  function changeFileSourceFilter(value: string) {
    setFileSourceFilter(value)
    setFileOffset(0)
  }

  function changeFileSearch(value: string) {
    setFileSearch(value)
    setFileOffset(0)
  }

  if (checkingAuth) {
    return <div className="min-h-screen bg-slate-950 flex items-center justify-center text-sm text-slate-400">正在加载会话…</div>
  }
  if (setupStatus?.needs_setup) {
    return (
      <SetupView
        status={setupStatus}
        onComplete={(u) => {
          setSetupStatus(null)
          setUser(u)
        }}
      />
    )
  }
  if (!user) {
    return <LoginView onAuthenticated={(u) => setUser(u)} />
  }

  const sourceMap = new Map(sources.map((s) => [s.id, s.local_alias]))

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="sticky top-0 z-30 border-b border-slate-800/80 bg-slate-950/85 backdrop-blur-md">
        <div className="mx-auto flex max-w-7xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-blue-600/20 text-blue-400 border border-blue-500/30">
              <CloudDownload className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2.5">
                <h1 className="text-base font-semibold tracking-tight">Piko Download Server</h1>
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] ${
                    sseState === 'connected'
                      ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                      : 'border-amber-500/30 bg-amber-500/10 text-amber-300'
                  }`}
                >
                  <span className={`h-1.5 w-1.5 rounded-full ${sseState === 'connected' ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse'}`} />
                  {sseState === 'connected' ? '实时连接' : sseState === 'connecting' ? '连接中' : '重连中'}
                </span>
              </div>
              <p className="text-xs text-slate-400">已登录管理员：{user.username}</p>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-end gap-2">
            <button
              onClick={() => setHideFilenames((value) => !value)}
              title={hideFilenames ? '显示明文文件名' : '隐藏文件名'}
              aria-pressed={hideFilenames}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs cursor-pointer ${
                hideFilenames
                  ? 'border-amber-500/40 bg-amber-500/10 text-amber-200'
                  : 'border-slate-800 bg-slate-900 text-slate-200 hover:bg-slate-800'
              }`}
            >
              {hideFilenames ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
              <span className="hidden sm:inline">{hideFilenames ? '文件名已隐藏' : '文件名可见'}</span>
            </button>
            <button
              onClick={() => void refreshAll()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800 cursor-pointer"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              刷新
            </button>
            <button
              onClick={async () => {
                await api.logout()
                setUser(null)
              }}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800 cursor-pointer"
            >
              <LogOut className="h-3.5 w-3.5" />
              退出
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-7xl space-y-6 px-4 py-6 sm:px-6">
        <section className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-4 flex items-center justify-between">
            <div>
              <div className="text-xs text-slate-400 flex items-center gap-1.5"><Activity className="h-3.5 w-3.5 text-blue-400" /> 实时总速度</div>
              <div className="mt-1.5 text-xl font-semibold tabular-nums">{formatSpeed(stats?.total_speed_bytes)}</div>
              <div className="mt-0.5 text-xs text-slate-400 tabular-nums">活动下载：{stats?.active_workers ?? 0}</div>
            </div>
            <SpeedSparkline samples={speedHistory} />
          </div>

          <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-4">
            <div className="text-xs text-slate-400 flex items-center gap-1.5"><Layers className="h-3.5 w-3.5 text-amber-400" /> 队列与暂停</div>
            <div className="mt-1.5 text-xl font-semibold tabular-nums">
              {stats?.queued_count ?? 0} <span className="text-sm font-normal text-slate-500">/</span> {stats?.paused_count ?? 0}
            </div>
            <div className="mt-0.5 text-xs text-slate-400">排队等待 / 已暂停</div>
          </div>

          <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-4">
            <div className="text-xs text-slate-400 flex items-center gap-1.5"><FileCheck2 className="h-3.5 w-3.5 text-emerald-400" /> 已交付 / 异常</div>
            <div className="mt-1.5 text-xl font-semibold tabular-nums">
              {stats?.completed_count ?? 0} <span className="text-sm font-normal text-slate-500">/</span>{' '}
              <span className={(stats?.failed_count ?? 0) > 0 ? 'text-rose-400' : ''}>{stats?.failed_count ?? 0}</span>
            </div>
            <div className="mt-0.5 text-xs text-slate-400">累计交付 / 失败或冲突</div>
          </div>

          <div className="rounded-2xl border border-slate-800 bg-slate-900/70 p-4">
            <div className="text-xs text-slate-400 flex items-center gap-1.5"><HardDrive className="h-3.5 w-3.5 text-cyan-400" /> 下载卷可用空间</div>
            <div className="mt-1.5 text-xl font-semibold tabular-nums">{formatBytes(stats?.free_space_bytes)}</div>
            <div className="mt-0.5 text-xs truncate" title={settings?.download_dir}>
              {stats?.low_disk_blocked ? (
                <span className="text-rose-400">空间低于阈值，已暂停新派发</span>
              ) : (
                <span className="text-slate-400 font-mono">{settings?.download_dir || '空间充足'}</span>
              )}
            </div>
          </div>
        </section>

        <nav className="flex flex-wrap items-center gap-2 border-b border-slate-800 pb-3">
          {[
            { key: 'tasks' as const, label: '下载任务', icon: CloudDownload, count: taskTotal },
            { key: 'files' as const, label: '文件索引', icon: FileCheck2, count: fileTotal },
            { key: 'logs' as const, label: '运行日志', icon: ScrollText, count: null },
            { key: 'manage' as const, label: '管理设置', icon: Settings, count: sources.length },
          ].map((tab) => {
            const Icon = tab.icon
            const active = activeTab === tab.key
            return (
              <button
                key={tab.key}
                onClick={() => switchTab(tab.key)}
                className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium transition cursor-pointer ${
                  active
                    ? 'bg-slate-800 text-white border border-slate-700'
                    : 'text-slate-400 hover:bg-slate-900 hover:text-slate-200 border border-transparent'
                }`}
              >
                <Icon className="h-4 w-4" />
                {tab.label}
                {tab.count !== null && (
                  <span className="rounded-full bg-slate-950 px-2 py-0.5 text-xs text-slate-400 tabular-nums">{tab.count}</span>
                )}
              </button>
            )
          })}
        </nav>

        {activeTab === 'tasks' && (
          <TasksTab
            tasks={tasks}
            sources={sources}
            stateFilter={taskStateFilter}
            sourceFilter={taskSourceFilter}
            searchQuery={taskSearch}
            hideFilenames={hideFilenames}
            total={taskTotal}
            limit={TASK_PAGE_SIZE}
            offset={taskOffset}
            onStateFilterChange={changeTaskStateFilter}
            onSourceFilterChange={changeTaskSourceFilter}
            onSearchChange={changeTaskSearch}
            onOffsetChange={setTaskOffset}
            onTaskAction={async (id, action) => {
              try {
                await api.taskAction(id, action)
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '操作失败')
              }
            }}
            onBatchAction={async (action) => {
              try {
                const res = await api.batchTasks(action, taskSourceFilter)
                notify('ok', `已处理 ${res.affected} 个任务`)
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '批量操作失败')
              }
            }}
            onInspect={(task) => setInspectedTask(task)}
          />
        )}

        {activeTab === 'manage' && (
          <ManageTab
            settings={settings}
            sources={sources}
            notificationChannels={notificationChannels}
            activeWorkers={stats?.active_workers ?? 0}
            onSaveSettings={async (patch) => {
              const updated = await api.updateSettings(patch)
              setSettings(updated)
              notify('ok', '设置已保存并生效')
              await refreshAll()
            }}
            onSaveProxy={async (proxyURL) => {
              const updated = await api.updateProxy(
                proxyURL === null ? { clear: true } : { proxy_url: proxyURL },
              )
              notify('ok', updated.proxy_configured ? '代理已启用并通过连接验证' : '代理已关闭')
              await refreshAll()
            }}
            onCreateSources={async (items) => {
              for (const item of items) {
                await api.createSource(item)
              }
              notify('ok', `已添加 ${items.length} 个监控目录并触发扫描`)
              await refreshAll()
            }}
            onUpdateSource={async (id, patch) => {
              try {
                await api.updateSource(id, patch)
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '更新失败')
              }
            }}
            onScanSource={async (id) => {
              try {
                await api.scanSource(id)
                notify('ok', '已触发扫描')
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '触发扫描失败')
              }
            }}
            onScanAllSources={async () => {
              try {
                const res = await api.scanAllSources()
                notify('ok', `已安排 ${res.scheduled} 个来源立即扫描`)
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '触发全部扫描失败')
              }
            }}
            onDeleteSource={async (id) => {
              try {
                await api.deleteSource(id)
                notify('ok', '已移除监控目录（本地文件已保留）')
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '移除失败')
              }
            }}
            onUpdateAccount={async (username, password) => {
              await api.updateAccount({ username, password })
              notify('ok', 'PikPak 账号已更新')
              await refreshAll()
            }}
            onChangeAdminPassword={async (currentPassword, newPassword) => {
              await api.changeAdminPassword({ current_password: currentPassword, new_password: newPassword })
            }}
            onCreateNotification={async (input) => {
              await api.createNotification(input)
              notify('ok', '通知渠道已添加')
              await refreshAll()
            }}
            onUpdateNotification={async (id, input) => {
              await api.updateNotification(id, input)
              await refreshAll()
            }}
            onDeleteNotification={async (id) => {
              await api.deleteNotification(id)
              notify('ok', '通知渠道已删除')
              await refreshAll()
            }}
            onTestNotification={async (id) => {
              await api.testNotification(id)
              notify('ok', '测试通知已发送')
            }}
            onSaveStatusInterval={async (minutes) => {
              const updated = await api.updateSettings({ notification_status_interval_minutes: minutes })
              setSettings(updated)
              notify('ok', '状态汇报间隔已保存')
              await refreshAll()
            }}
          />
        )}

        {activeTab === 'files' && (
          <FilesTab
            files={files}
            sources={sources}
            remoteStateFilter={fileRemoteState}
            sourceFilter={fileSourceFilter}
            searchQuery={fileSearch}
            hideFilenames={hideFilenames}
            total={fileTotal}
            limit={FILE_PAGE_SIZE}
            offset={fileOffset}
            onRemoteStateChange={changeFileRemoteState}
            onSourceFilterChange={changeFileSourceFilter}
            onSearchChange={changeFileSearch}
            onOffsetChange={setFileOffset}
          />
        )}

        {activeTab === 'logs' && <LogsTab />}
      </main>

      <TaskDetailDrawer
        task={inspectedTask}
        hideFilenames={hideFilenames}
        sourceName={inspectedTask ? sourceMap.get(inspectedTask.source_id) || inspectedTask.source_id : ''}
        onClose={() => setInspectedTask(null)}
        onAction={async (id, action) => {
          try {
            await api.taskAction(id, action)
            await refreshAll()
          } catch (err) {
            notify('error', err instanceof Error ? err.message : '操作失败')
          }
        }}
      />

      {toast && (
        <div
          className={`fixed bottom-5 right-5 z-50 rounded-xl border px-4 py-2.5 text-xs font-medium shadow-xl ${
            toast.kind === 'error'
              ? 'border-rose-500/40 bg-rose-950/90 text-rose-200'
              : 'border-emerald-500/40 bg-emerald-950/90 text-emerald-200'
          }`}
        >
          {toast.text}
        </div>
      )}
    </div>
  )
}
