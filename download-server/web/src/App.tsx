import { useCallback, useEffect, useRef, useState } from 'react'
import * as api from './api'
import type {
  AuthUser,
  FileView,
  NotificationChannel,
  SetupStatus,
  Source,
  SystemSettings,
  SystemStats,
  TaskView,
  UpdateInfo,
} from './types'
import { AppShell, ShellPlaceholder, type SseState, type TabKey } from './components/AppShell'
import { OverviewTab } from './components/OverviewTab'
import { LoginView } from './components/LoginView'
import { SetupView } from './components/SetupView'
import { TasksTab } from './components/TasksTab'
import { ManageTab, type ManageSection } from './components/ManageTab'
import { FilesTab } from './components/FilesTab'
import { LogsTab } from './components/LogsTab'
import { TaskDetailDrawer } from './components/TaskDetailDrawer'
import { UpdateDialog } from './components/UpdateDialog'
import { useDebouncedValue } from './useDebouncedValue'

interface RefreshOptions {
  stats: boolean
  settings: boolean
  sources: boolean
  tasks: boolean
  files: boolean
  notifications: boolean
  overview: boolean
}

function refreshScope(partial: Partial<RefreshOptions>): RefreshOptions {
  return {
    stats: false,
    settings: false,
    sources: false,
    tasks: false,
    files: false,
    notifications: false,
    overview: false,
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
  overview: true,
})

const FILENAME_VISIBILITY_KEY = 'piko.download.hideFilenames'
const TASK_PAGE_SIZE = 50
const FILE_PAGE_SIZE = 50
const TAB_TITLES: Record<TabKey, string> = {
  overview: '概览',
  tasks: '下载任务',
  files: '文件索引',
  logs: '运行日志',
  manage: '管理设置',
}

function tabFromPath(pathname: string): TabKey {
  if (pathname.startsWith('/manage') || pathname.startsWith('/sources')) return 'manage'
  if (pathname.startsWith('/files')) return 'files'
  if (pathname.startsWith('/logs')) return 'logs'
  if (pathname.startsWith('/tasks')) return 'tasks'
  return 'overview'
}

function tabPath(tab: TabKey): string {
  return tab === 'overview' ? '/' : `/${tab}`
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
  const [sseState, setSseState] = useState<SseState>('connecting')

  const [stats, setStats] = useState<SystemStats | null>(null)
  const [settings, setSettings] = useState<SystemSettings | null>(null)
  const [speedHistory, setSpeedHistory] = useState<number[]>([0, 0])
  const [sources, setSources] = useState<Source[]>([])
  const [notificationChannels, setNotificationChannels] = useState<NotificationChannel[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [taskTotal, setTaskTotal] = useState(0)
  const [taskOffset, setTaskOffset] = useState(0)
  const [overviewTasks, setOverviewTasks] = useState<TaskView[]>([])
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
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null)
  const [showUpdateDialog, setShowUpdateDialog] = useState(false)
  const [checkingUpdate, setCheckingUpdate] = useState(false)
  const [applyingUpdate, setApplyingUpdate] = useState(false)
  const [restartingForUpdate, setRestartingForUpdate] = useState(false)
  const [updateError, setUpdateError] = useState('')
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const [manageSection, setManageSection] = useState<ManageSection>('storage')
  const [manageDetailOpen, setManageDetailOpen] = useState(false)

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
    document.title = `${TAB_TITLES[activeTab]} · Piko Download Server`
  }, [activeTab])

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

  const refreshAll = useCallback(
    async (options: RefreshOptions = REFRESH_ALL) => {
      const taskRequestSeq = options.tasks ? ++taskRequestSeqRef.current : 0
      const fileRequestSeq = options.files ? ++fileRequestSeqRef.current : 0
      try {
        const [nextStats, nextSettings, nextSources, nextTasks, nextFiles, nextNotifications, nextOverview] = await Promise.all([
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
          options.overview ? api.listTasks({ limit: 12, offset: 0 }) : Promise.resolve(null),
        ])
        if (nextStats) {
          setStats(nextStats)
          setSpeedHistory((prev) => [...prev.slice(-24), nextStats.total_speed_bytes])
        }
        if (nextSettings) setSettings(nextSettings)
        if (nextSources) setSources(nextSources.sources || [])
        if (nextNotifications) setNotificationChannels(nextNotifications.channels || [])
        if (nextOverview) {
          const nextOverviewTasks = nextOverview.tasks || []
          setOverviewTasks(nextOverviewTasks)
          setInspectedTask((prev) => (prev ? nextOverviewTasks.find((task) => task.id === prev.id) || prev : null))
        }
        if (nextTasks && taskRequestSeq === taskRequestSeqRef.current) {
          const nextTaskList = nextTasks.tasks || []
          const nextTaskTotal = nextTasks.total || 0
          setTasks(nextTaskList)
          setTaskTotal(nextTaskTotal)
          if (taskOffset > 0 && nextTaskList.length === 0 && nextTaskTotal > 0) {
            setTaskOffset(Math.floor((nextTaskTotal - 1) / TASK_PAGE_SIZE) * TASK_PAGE_SIZE)
          }
          setInspectedTask((prev) => (prev ? nextTaskList.find((task) => task.id === prev.id) || prev : null))
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
    },
    [
      taskStateFilter,
      taskSourceFilter,
      debouncedTaskSearch,
      taskOffset,
      fileRemoteState,
      fileSourceFilter,
      debouncedFileSearch,
      fileOffset,
      notify,
    ],
  )

  const scheduleRefresh = useCallback((scope: RefreshOptions) => {
    const pending = pendingRefreshRef.current
    pendingRefreshRef.current = {
      stats: pending.stats || scope.stats,
      settings: pending.settings || scope.settings,
      sources: pending.sources || scope.sources,
      tasks: pending.tasks || scope.tasks,
      files: pending.files || scope.files,
      notifications: pending.notifications || scope.notifications,
      overview: pending.overview || scope.overview,
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
    api.setUnauthorizedHandler(() => setUser(null))
    api
      .getSetupStatus()
      .then(async (status) => {
        setSetupStatus(status)
        if (status.needs_setup) return
        try {
          setUser(await api.me())
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
    let cancelled = false
    const load = async () => {
      try {
        const info = await api.getUpdate()
        if (!cancelled) setUpdateInfo(info)
      } catch {
        // Keep the last known version when an automatic check fails.
      }
    }
    void load()
    const timer = window.setInterval(load, 30 * 60 * 1000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [user])

  const refreshUpdate = useCallback(async (refresh: boolean) => {
    setCheckingUpdate(true)
    setUpdateError('')
    try {
      setUpdateInfo(await api.getUpdate(refresh))
    } catch (err) {
      setUpdateError(err instanceof Error ? err.message : '检查更新失败')
    } finally {
      setCheckingUpdate(false)
    }
  }, [])

  const openUpdateDialog = useCallback(() => {
    setShowUpdateDialog(true)
    void refreshUpdate(true)
  }, [refreshUpdate])

  const startUpdate = useCallback(async () => {
    if (!updateInfo?.available || !updateInfo.can_apply || applyingUpdate || restartingForUpdate) return
    setApplyingUpdate(true)
    setUpdateError('')
    try {
      const result = await api.applyUpdate()
      setApplyingUpdate(false)
      setRestartingForUpdate(true)
      void (async () => {
        const deadline = Date.now() + 120 * 1000
        while (Date.now() < deadline) {
          await new Promise((resolve) => window.setTimeout(resolve, 1200))
          try {
            const health = await api.getHealth()
            if (health.version === result.version) {
              window.location.reload()
              return
            }
          } catch {
            // The server is still down or replacing its process.
          }
        }
        setRestartingForUpdate(false)
        setUpdateError('服务重启超时，请手动刷新页面或查看运行日志')
      })()
    } catch (err) {
      setApplyingUpdate(false)
      setUpdateError(err instanceof Error ? err.message : '升级失败')
    }
  }, [applyingUpdate, restartingForUpdate, updateInfo])

  useEffect(() => {
    if (!user) return
    setSseState('connecting')
    const eventSource = new EventSource(api.apiURL('/api/events'), { withCredentials: true })
    eventSource.addEventListener('ready', () => {
      setSseState('connected')
      void refreshAll()
    })
    const handleTaskProgress = () => scheduleRefresh(refreshScope({ stats: true, tasks: true }))
    const handleTaskOutcome = () => scheduleRefresh(refreshScope({ stats: true, tasks: true, files: true, overview: true }))
    const handleCatalog = () => scheduleRefresh(refreshScope({ stats: true, sources: true, tasks: true, files: true, overview: true }))
    const handleSettings = () => scheduleRefresh(refreshScope({ stats: true, settings: true }))
    const handleStats = () => scheduleRefresh(refreshScope({ stats: true }))
    eventSource.addEventListener('task.updated', handleTaskProgress)
    ;['task.started', 'task.completed', 'task.failed', 'task.conflict', 'task.retry', 'tasks.queued'].forEach((name) => {
      eventSource.addEventListener(name, handleTaskOutcome)
    })
    ;['source.updated', 'scan.started', 'scan.finished', 'scan.failed', 'scan.retrying'].forEach((name) => {
      eventSource.addEventListener(name, handleCatalog)
    })
    eventSource.addEventListener('settings.updated', handleSettings)
    ;['system.stats', 'system.low_disk', 'system.disk_ok', 'status.report'].forEach((name) => {
      eventSource.addEventListener(name, handleStats)
    })
    eventSource.onerror = () => setSseState('reconnecting')
    return () => {
      eventSource.close()
      if (refreshTimerRef.current !== null) {
        window.clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
      pendingRefreshRef.current = refreshScope({})
    }
  }, [user, refreshAll, scheduleRefresh])

  function switchTab(tab: TabKey) {
    setActiveTab(tab)
    if (tab === 'manage' && window.innerWidth < 1024) {
      setManageDetailOpen(false)
    }
    const targetPath = tabPath(tab)
    if (window.location.pathname !== targetPath) {
      window.history.pushState(null, '', targetPath)
    }
  }

  function showTasks(state = '') {
    setTaskStateFilter(state)
    setTaskOffset(0)
    switchTab('tasks')
  }

  function showManage(section?: ManageSection) {
    switchTab('manage')
    if (section) {
      setManageSection(section)
      if (window.innerWidth < 1024) {
        setManageDetailOpen(true)
      }
    }
  }

  async function scanAllSources() {
    try {
      const result = await api.scanAllSources()
      notify('ok', `已安排 ${result.scheduled} 个来源立即扫描`)
      await refreshAll()
    } catch (err) {
      notify('error', err instanceof Error ? err.message : '触发全部扫描失败')
    }
  }

  async function handleLogout() {
    await api.logout()
    setUser(null)
  }

  if (checkingAuth) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-canvas">
        <ShellPlaceholder label="正在加载会话…" />
      </div>
    )
  }

  if (setupStatus?.needs_setup) {
    return (
      <SetupView
        status={setupStatus}
        onComplete={(nextUser) => {
          setSetupStatus(null)
          setUser(nextUser)
        }}
      />
    )
  }

  if (!user) {
    return <LoginView onAuthenticated={setUser} />
  }

  return (
    <>
      <AppShell
        tab={activeTab}
        onTab={switchTab}
        user={user}
        sseState={sseState}
        version={updateInfo?.current_version}
        updateAvailable={updateInfo?.available ?? false}
        hideFilenames={hideFilenames}
        taskCount={taskTotal}
        activeTaskCount={(stats?.active_workers ?? 0) + (stats?.queued_count ?? 0)}
        fileCount={fileTotal}
        onToggleFilenames={() => setHideFilenames((value) => !value)}
        onRefresh={() => void refreshAll()}
        onOpenLogs={() => switchTab('logs')}
        onOpenUpdate={openUpdateDialog}
        onLogout={() => void handleLogout()}
      >
        {activeTab === 'overview' && (
          <OverviewTab
            stats={stats}
            settings={settings}
            sources={sources}
            tasks={overviewTasks}
            speedHistory={speedHistory}
            updateInfo={updateInfo}
            hideFilenames={hideFilenames}
            onShowTasks={showTasks}
            onShowFiles={() => switchTab('files')}
            onShowManage={showManage}
            onShowLogs={() => switchTab('logs')}
            onOpenUpdate={openUpdateDialog}
            onScanAll={() => void scanAllSources()}
            onInspect={(task) => setInspectedTask(task)}
          />
        )}

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
            onStateFilterChange={(value) => {
              setTaskStateFilter(value)
              setTaskOffset(0)
            }}
            onSourceFilterChange={(value) => {
              setTaskSourceFilter(value)
              setTaskOffset(0)
            }}
            onSearchChange={(value) => {
              setTaskSearch(value)
              setTaskOffset(0)
            }}
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
                const result = await api.batchTasks(action, taskSourceFilter)
                notify('ok', `已处理 ${result.affected} 个任务`)
                await refreshAll()
              } catch (err) {
                notify('error', err instanceof Error ? err.message : '批量操作失败')
              }
            }}
            onInspect={(task) => setInspectedTask(task)}
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
            onRemoteStateChange={(value) => {
              setFileRemoteState(value)
              setFileOffset(0)
            }}
            onSourceFilterChange={(value) => {
              setFileSourceFilter(value)
              setFileOffset(0)
            }}
            onSearchChange={(value) => {
              setFileSearch(value)
              setFileOffset(0)
            }}
            onOffsetChange={setFileOffset}
          />
        )}

        {activeTab === 'logs' && <LogsTab />}

        {activeTab === 'manage' && (
          <ManageTab
            settings={settings}
            sources={sources}
            notificationChannels={notificationChannels}
            activeWorkers={stats?.active_workers ?? 0}
            section={manageSection}
            detailOpen={manageDetailOpen}
            updateInfo={updateInfo}
            onSectionChange={setManageSection}
            onDetailOpenChange={setManageDetailOpen}
            onOpenUpdate={openUpdateDialog}
            onOpenLogs={() => switchTab('logs')}
            onSaveSettings={async (patch) => {
              const updated = await api.updateSettings(patch)
              setSettings(updated)
              notify('ok', '设置已保存并生效')
              await refreshAll()
            }}
            onSaveProxy={async (proxyURL) => {
              const updated = await api.updateProxy(proxyURL === null ? { clear: true } : { proxy_url: proxyURL })
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
            onScanAllSources={scanAllSources}
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
      </AppShell>

      <TaskDetailDrawer
        task={inspectedTask}
        hideFilenames={hideFilenames}
        sourceName={inspectedTask ? sources.find((source) => source.id === inspectedTask.source_id)?.local_alias || inspectedTask.source_id : ''}
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

      {showUpdateDialog && (
        <UpdateDialog
          info={updateInfo}
          checking={checkingUpdate}
          applying={applyingUpdate}
          restarting={restartingForUpdate}
          error={updateError}
          onClose={() => setShowUpdateDialog(false)}
          onApply={() => void startUpdate()}
          onRecheck={() => void refreshUpdate(true)}
        />
      )}

      {toast && (
        <div
          className={`fixed inset-x-4 bottom-24 z-50 rounded-xl border px-3.5 py-2.5 text-xs font-medium shadow-2xl sm:inset-x-auto sm:bottom-6 sm:right-6 sm:max-w-sm ${
            toast.kind === 'error'
              ? 'border-rose-500/30 bg-rose-950/95 text-rose-200'
              : 'border-emerald-500/30 bg-emerald-950/95 text-emerald-200'
          }`}
          role="status"
        >
          {toast.text}
        </div>
      )}
    </>
  )
}
