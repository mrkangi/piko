import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Activity,
  CloudDownload,
  HardDrive,
  Layers,
  LogOut,
  RefreshCw,
  Settings,
  FileCheck2,
} from 'lucide-react'
import * as api from './api'
import { formatBytes, formatSpeed } from './format'
import type { AuthUser, FileView, SetupStatus, Source, SystemSettings, SystemStats, TaskView } from './types'
import { LoginView } from './components/LoginView'
import { SetupView } from './components/SetupView'
import { SpeedSparkline } from './components/SpeedSparkline'
import { TasksTab } from './components/TasksTab'
import { ManageTab } from './components/ManageTab'
import { FilesTab } from './components/FilesTab'
import { TaskDetailDrawer } from './components/TaskDetailDrawer'

type TabKey = 'tasks' | 'files' | 'manage'

function tabFromPath(pathname: string): TabKey {
  if (pathname.startsWith('/manage') || pathname.startsWith('/sources')) return 'manage'
  if (pathname.startsWith('/files')) return 'files'
  return 'tasks'
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
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [files, setFiles] = useState<FileView[]>([])

  const [taskStateFilter, setTaskStateFilter] = useState('')
  const [taskSourceFilter, setTaskSourceFilter] = useState('')
  const [taskSearch, setTaskSearch] = useState('')

  const [fileRemoteState, setFileRemoteState] = useState('')
  const [fileSourceFilter, setFileSourceFilter] = useState('')
  const [fileSearch, setFileSearch] = useState('')

  const [inspectedTask, setInspectedTask] = useState<TaskView | null>(null)
  const [toast, setToast] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const refreshTimerRef = useRef<number | null>(null)

  const notify = useCallback((kind: 'ok' | 'error', text: string) => {
    setToast({ kind, text })
  }, [])

  useEffect(() => {
    if (!toast) return
    const id = window.setTimeout(() => setToast(null), 3500)
    return () => window.clearTimeout(id)
  }, [toast])

  const refreshAll = useCallback(async () => {
    try {
      const [nextStats, nextSettings, nextSources, nextTasks, nextFiles] = await Promise.all([
        api.getStats(),
        api.getSettings(),
        api.listSources(),
        api.listTasks({ state: taskStateFilter, source_id: taskSourceFilter, q: taskSearch }),
        api.listFiles({ remote_state: fileRemoteState, source_id: fileSourceFilter, q: fileSearch }),
      ])
      setStats(nextStats)
      setSettings(nextSettings)
      setSpeedHistory((prev) => [...prev.slice(-24), nextStats.total_speed_bytes])
      setSources(nextSources.sources || [])
      const nextTaskList = nextTasks.tasks || []
      setTasks(nextTaskList)
      setInspectedTask((prev) => (prev ? nextTaskList.find((t) => t.id === prev.id) || null : null))
      setFiles(nextFiles.files || [])
    } catch (err) {
      if (err instanceof Error && err.message !== '请先登录') {
        notify('error', err.message)
      }
    }
  }, [taskStateFilter, taskSourceFilter, taskSearch, fileRemoteState, fileSourceFilter, fileSearch, notify])

  const scheduleRefresh = useCallback(() => {
    if (refreshTimerRef.current !== null) return
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null
      void refreshAll()
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
    const handleEvent = () => scheduleRefresh()
    ;['task.updated', 'source.updated', 'scan.started', 'scan.finished', 'system.stats', 'settings.updated'].forEach((name) => {
      es.addEventListener(name, handleEvent)
    })
    es.onerror = () => setSseState('reconnecting')
    return () => {
      es.close()
      if (refreshTimerRef.current !== null) {
        window.clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
    }
  }, [user, refreshAll, scheduleRefresh])

  function switchTab(tab: TabKey) {
    setActiveTab(tab)
    const targetPath = `/${tab}`
    if (window.location.pathname !== targetPath) {
      window.history.pushState(null, '', targetPath)
    }
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

          <div className="flex items-center gap-2">
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

        <nav className="flex items-center gap-2 border-b border-slate-800 pb-3">
          {[
            { key: 'tasks' as const, label: '下载任务', icon: CloudDownload, count: tasks.length },
            { key: 'files' as const, label: '文件索引', icon: FileCheck2, count: files.length },
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
                <span className="rounded-full bg-slate-950 px-2 py-0.5 text-xs text-slate-400 tabular-nums">{tab.count}</span>
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
            onStateFilterChange={setTaskStateFilter}
            onSourceFilterChange={setTaskSourceFilter}
            onSearchChange={setTaskSearch}
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
            activeWorkers={stats?.active_workers ?? 0}
            onSaveSettings={async (patch) => {
              const updated = await api.updateSettings(patch)
              setSettings(updated)
              notify('ok', '设置已保存并生效')
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
          />
        )}

        {activeTab === 'files' && (
          <FilesTab
            files={files}
            sources={sources}
            remoteStateFilter={fileRemoteState}
            sourceFilter={fileSourceFilter}
            searchQuery={fileSearch}
            onRemoteStateChange={setFileRemoteState}
            onSourceFilterChange={setFileSourceFilter}
            onSearchChange={setFileSearch}
          />
        )}
      </main>

      <TaskDetailDrawer
        task={inspectedTask}
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
