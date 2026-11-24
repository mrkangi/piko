import { Pause, Play, RotateCcw, Search, XCircle, Info } from 'lucide-react'
import type { Source, TaskView } from '../types'
import { TASK_STATE_META, formatBytes, formatETA, formatSpeed } from '../format'

interface Props {
  tasks: TaskView[]
  sources: Source[]
  stateFilter: string
  sourceFilter: string
  searchQuery: string
  onStateFilterChange: (state: string) => void
  onSourceFilterChange: (sourceId: string) => void
  onSearchChange: (q: string) => void
  onTaskAction: (id: string, action: 'pause' | 'resume' | 'cancel' | 'retry') => Promise<void>
  onBatchAction: (action: 'pause' | 'resume' | 'retry') => Promise<void>
  onInspect: (task: TaskView) => void
}

const STATE_TABS = [
  { key: '', label: '全部' },
  { key: 'RUNNING', label: '下载中' },
  { key: 'QUEUED', label: '排队中' },
  { key: 'PAUSED', label: '已暂停' },
  { key: 'RETRY_WAIT', label: '等待重试' },
  { key: 'FAILED', label: '失败' },
  { key: 'CONFLICT', label: '冲突' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELED', label: '已取消' },
]

export function TasksTab({
  tasks,
  sources,
  stateFilter,
  sourceFilter,
  searchQuery,
  onStateFilterChange,
  onSourceFilterChange,
  onSearchChange,
  onTaskAction,
  onBatchAction,
  onInspect,
}: Props) {
  const sourceMap = new Map(sources.map((s) => [s.id, s.local_alias]))

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex flex-wrap gap-1.5">
          {STATE_TABS.map((tab) => {
            const active = stateFilter === tab.key
            return (
              <button
                key={tab.key || 'all'}
                onClick={() => onStateFilterChange(tab.key)}
                className={`rounded-lg px-3 py-1.5 text-xs font-medium transition cursor-pointer ${
                  active
                    ? 'bg-blue-600 text-white shadow-xs'
                    : 'border border-slate-800 bg-slate-900 text-slate-400 hover:text-slate-200'
                }`}
              >
                {tab.label}
              </button>
            )
          })}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative flex-1 sm:w-56 sm:flex-none">
            <Search className="absolute left-3 top-2.5 h-3.5 w-3.5 text-slate-500" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="搜索文件名或路径…"
              className="w-full rounded-lg border border-slate-800 bg-slate-900 pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:border-blue-500 focus:outline-none"
            />
          </div>
          <select
            value={sourceFilter}
            onChange={(e) => onSourceFilterChange(e.target.value)}
            className="rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 focus:border-blue-500 focus:outline-none"
          >
            <option value="">全部来源</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>{s.local_alias}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-slate-800/80 bg-slate-900/60 px-4 py-2.5">
        <span className="text-xs text-slate-400">当前视图共 <strong className="text-slate-200 tabular-nums">{tasks.length}</strong> 个任务</span>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => onBatchAction('pause')}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
          >
            <Pause className="h-3.5 w-3.5" /> 暂停进行中
          </button>
          <button
            onClick={() => onBatchAction('resume')}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
          >
            <Play className="h-3.5 w-3.5" /> 继续已暂停
          </button>
          <button
            onClick={() => onBatchAction('retry')}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
          >
            <RotateCcw className="h-3.5 w-3.5" /> 重试异常
          </button>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-slate-800 bg-slate-900/70">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-800 bg-slate-950/60 text-xs uppercase text-slate-400">
              <tr>
                <th className="px-4 py-3 font-medium">目标路径 / 来源</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">进度</th>
                <th className="px-4 py-3 font-medium">速率 / ETA</th>
                <th className="px-4 py-3 font-medium text-right">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/70">
              {tasks.length === 0 ? (
                <tr>
                  <td colSpan={5} className="px-4 py-12 text-center text-sm text-slate-500">
                    当前筛选条件下没有下载任务
                  </td>
                </tr>
              ) : (
                tasks.map((task) => {
                  const meta = TASK_STATE_META[task.state]
                  const canPause = ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'VERIFYING'].includes(task.state)
                  const canResume = task.state === 'PAUSED'
                  const canRetry = ['FAILED', 'CANCELED', 'CONFLICT'].includes(task.state)
                  const canCancel = !['COMPLETED', 'CANCELED', 'CANCELING', 'COMMITTING'].includes(task.state)

                  return (
                    <tr key={task.id} className="hover:bg-slate-800/35 transition-colors">
                      <td className="px-4 py-3 max-w-md">
                        <div className="flex items-center gap-2">
                          <span className="truncate font-medium text-slate-100" title={task.target_rel_path}>
                            {task.target_rel_path}
                          </span>
                        </div>
                        <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
                          <span className="rounded bg-slate-800 px-1.5 py-0.5 text-[11px] text-slate-300">
                            {sourceMap.get(task.source_id) || task.source_id}
                          </span>
                          <span className="truncate" title={task.remote_path}>{task.remote_path}</span>
                        </div>
                        {task.error_message && (
                          <div className="mt-1 truncate text-xs text-rose-300" title={task.error_message}>
                            {task.error_message}
                          </div>
                        )}
                      </td>

                      <td className="px-4 py-3 whitespace-nowrap">
                        <span className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium ${meta.badgeClass}`}>
                          {meta.label}
                        </span>
                      </td>

                      <td className="px-4 py-3 min-w-[180px]">
                        <div className="h-2 w-full overflow-hidden rounded-full bg-slate-800">
                          <div
                            className={`h-full transition-all duration-300 ${meta.progressClass}`}
                            style={{ width: `${task.progress_percent}%` }}
                          />
                        </div>
                        <div className="mt-1 flex justify-between text-xs text-slate-400 tabular-nums">
                          <span>{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
                          <span>{task.progress_percent.toFixed(1)}%</span>
                        </div>
                      </td>

                      <td className="px-4 py-3 whitespace-nowrap tabular-nums">
                        <div className="text-xs font-medium text-slate-200">{formatSpeed(task.speed_bytes_per_sec)}</div>
                        <div className="text-xs text-slate-400">ETA {formatETA(task.eta_seconds)}</div>
                      </td>

                      <td className="px-4 py-3 whitespace-nowrap text-right">
                        <div className="inline-flex items-center gap-1.5">
                          {canPause && (
                            <button
                              onClick={() => onTaskAction(task.id, 'pause')}
                              title="暂停"
                              className="rounded-lg border border-slate-700 bg-slate-800 p-1.5 text-slate-200 hover:bg-slate-700 cursor-pointer"
                            >
                              <Pause className="h-3.5 w-3.5" />
                            </button>
                          )}
                          {canResume && (
                            <button
                              onClick={() => onTaskAction(task.id, 'resume')}
                              title="继续"
                              className="rounded-lg bg-blue-600 p-1.5 text-white hover:bg-blue-500 cursor-pointer"
                            >
                              <Play className="h-3.5 w-3.5" />
                            </button>
                          )}
                          {canRetry && (
                            <button
                              onClick={() => onTaskAction(task.id, 'retry')}
                              title="重试"
                              className="rounded-lg bg-blue-600 p-1.5 text-white hover:bg-blue-500 cursor-pointer"
                            >
                              <RotateCcw className="h-3.5 w-3.5" />
                            </button>
                          )}
                          {canCancel && (
                            <button
                              onClick={() => onTaskAction(task.id, 'cancel')}
                              title="取消"
                              className="rounded-lg border border-rose-500/40 bg-rose-500/10 p-1.5 text-rose-300 hover:bg-rose-500/20 cursor-pointer"
                            >
                              <XCircle className="h-3.5 w-3.5" />
                            </button>
                          )}
                          <button
                            onClick={() => onInspect(task)}
                            title="详情"
                            className="rounded-lg border border-slate-800 bg-slate-950 p-1.5 text-slate-400 hover:text-slate-200 cursor-pointer"
                          >
                            <Info className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
