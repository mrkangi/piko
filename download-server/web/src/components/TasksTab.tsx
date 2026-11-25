import { useState } from 'react'
import type { ReactNode } from 'react'
import { CloudDownload, Info, Pause, Play, RotateCcw, Search, SlidersHorizontal, XCircle } from 'lucide-react'
import type { Source, TaskView } from '../types'
import { TASK_STATE_META, formatBytes, formatETA, formatSpeed } from '../format'
import { maskPath, maskTextPaths } from '../privacy'
import { Badge, Button, EmptyState, ProgressBar, Segmented, StatusDot, cn, inputClassName } from './ui'
import { Pagination } from './Pagination'

interface Props {
  tasks: TaskView[]
  sources: Source[]
  stateFilter: string
  sourceFilter: string
  searchQuery: string
  hideFilenames: boolean
  total: number
  limit: number
  offset: number
  onStateFilterChange: (state: string) => void
  onSourceFilterChange: (sourceId: string) => void
  onSearchChange: (q: string) => void
  onOffsetChange: (offset: number) => void
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

type TaskAction = 'pause' | 'resume' | 'cancel' | 'retry'

function TaskActions({
  task,
  onTaskAction,
  onInspect,
}: {
  task: TaskView
  onTaskAction: (id: string, action: TaskAction) => Promise<void>
  onInspect: (task: TaskView) => void
}) {
  const canPause = ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'VERIFYING'].includes(task.state)
  const canResume = task.state === 'PAUSED'
  const canRetry = ['FAILED', 'CANCELED', 'CONFLICT'].includes(task.state)
  const canCancel = !['COMPLETED', 'CANCELED', 'CANCELING', 'COMMITTING'].includes(task.state)

  return (
    <div className="inline-flex items-center gap-1">
      {canPause && (
        <Button variant="secondary" size="icon-sm" onClick={() => onTaskAction(task.id, 'pause')} title="暂停" aria-label="暂停">
          <Pause className="h-3.5 w-3.5" />
        </Button>
      )}
      {canResume && (
        <Button variant="primary" size="icon-sm" onClick={() => onTaskAction(task.id, 'resume')} title="继续" aria-label="继续">
          <Play className="h-3.5 w-3.5" />
        </Button>
      )}
      {canRetry && (
        <Button variant="primary" size="icon-sm" onClick={() => onTaskAction(task.id, 'retry')} title="重试" aria-label="重试">
          <RotateCcw className="h-3.5 w-3.5" />
        </Button>
      )}
      {canCancel && (
        <Button variant="danger" size="icon-sm" onClick={() => onTaskAction(task.id, 'cancel')} title="取消" aria-label="取消">
          <XCircle className="h-3.5 w-3.5" />
        </Button>
      )}
      <Button variant="ghost" size="icon-sm" onClick={() => onInspect(task)} title="详情" aria-label="详情">
        <Info className="h-3.5 w-3.5" />
      </Button>
    </div>
  )
}

function TaskError({ task, hideFilenames }: { task: TaskView; hideFilenames: boolean }) {
  if (!task.error_message) return null
  const message = maskTextPaths(task.error_message, [task.target_rel_path, task.remote_path, task.staging_rel_path], hideFilenames)
  return <div className="mt-1.5 line-clamp-2 break-all text-[11px] leading-4 text-rose-300/90">{message}</div>
}

function TaskName({
  task,
  sourceName,
  hideFilenames,
}: {
  task: TaskView
  sourceName: string
  hideFilenames: boolean
}) {
  return (
    <>
      <div className="truncate text-[13px] font-medium text-ink" title={hideFilenames ? undefined : task.target_rel_path}>
        {maskPath(task.target_rel_path, hideFilenames)}
      </div>
      <div className="mt-1 flex min-w-0 items-center gap-1.5 text-[11px] text-ink-3">
        <span className="shrink-0 rounded border border-line bg-surface-2 px-1.5 py-px text-[10px] text-ink-2">{sourceName}</span>
        <span className="truncate" title={hideFilenames ? undefined : task.remote_path}>
          {maskPath(task.remote_path, hideFilenames)}
        </span>
      </div>
    </>
  )
}

export function TasksTab({
  tasks,
  sources,
  stateFilter,
  sourceFilter,
  searchQuery,
  hideFilenames,
  total,
  limit,
  offset,
  onStateFilterChange,
  onSourceFilterChange,
  onSearchChange,
  onOffsetChange,
  onTaskAction,
  onBatchAction,
  onInspect,
}: Props) {
  const [filtersOpen, setFiltersOpen] = useState(false)
  const sourceMap = new Map(sources.map((source) => [source.id, source.local_alias]))
  const hasFilters = Boolean(stateFilter || sourceFilter || searchQuery.trim())

  function clearFilters() {
    onStateFilterChange('')
    onSourceFilterChange('')
    onSearchChange('')
    onOffsetChange(0)
  }

  return (
    <div className="space-y-3">
      <div className="space-y-2.5">
        <div className="flex flex-col gap-2.5 lg:flex-row lg:items-center lg:justify-between">
          <Segmented value={stateFilter} options={STATE_TABS} onChange={onStateFilterChange} />
          <div className="flex items-center gap-2">
            <div className="relative min-w-0 flex-1 lg:w-64 lg:flex-none">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-3" />
              <input
                type="search"
                value={searchQuery}
                onChange={(event) => onSearchChange(event.target.value)}
                placeholder="搜索文件名或路径…"
                className={cn(inputClassName, 'h-9 py-0 pl-8 text-xs')}
              />
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => setFiltersOpen((value) => !value)}
              aria-expanded={filtersOpen}
              className={cn(filtersOpen && 'border-line-strong bg-surface-3', 'lg:hidden')}
            >
              <SlidersHorizontal className="h-3.5 w-3.5" />
              筛选
            </Button>
            <select
              value={sourceFilter}
              onChange={(event) => onSourceFilterChange(event.target.value)}
              className={cn(inputClassName, 'hidden h-9 w-auto py-0 text-xs lg:block')}
            >
              <option value="">全部来源</option>
              {sources.map((source) => (
                <option key={source.id} value={source.id}>{source.local_alias}</option>
              ))}
            </select>
          </div>
        </div>

        {filtersOpen && (
          <div className="flex flex-col gap-2 rounded-lg border border-line bg-surface-1 p-2.5 lg:hidden">
            <FieldLabel>来源</FieldLabel>
            <select
              value={sourceFilter}
              onChange={(event) => onSourceFilterChange(event.target.value)}
              className={cn(inputClassName, 'h-9 py-0 text-xs')}
            >
              <option value="">全部来源</option>
              {sources.map((source) => (
                <option key={source.id} value={source.id}>{source.local_alias}</option>
              ))}
            </select>
            {hasFilters && (
              <Button variant="ghost" size="sm" onClick={clearFilters} className="self-start">
                清除全部筛选
              </Button>
            )}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 rounded-lg border border-line bg-surface-1 px-3 py-2">
          <span className="text-[11px] text-ink-3">
            共 <strong className="font-medium text-ink-2 tabular-nums">{total}</strong> 个任务
            <span className="mx-1.5 text-line-strong">·</span>本页 {tasks.length} 个
          </span>
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="hidden text-[11px] text-ink-3 sm:inline">批量作用于当前来源筛选</span>
            <Button variant="ghost" size="sm" onClick={() => void onBatchAction('pause')}>
              <Pause className="h-3.5 w-3.5" />
              暂停
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void onBatchAction('resume')}>
              <Play className="h-3.5 w-3.5" />
              继续
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void onBatchAction('retry')}>
              <RotateCcw className="h-3.5 w-3.5" />
              重试
            </Button>
          </div>
        </div>
      </div>

      <div className="hidden overflow-hidden rounded-xl border border-line bg-surface-1 md:block">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-left text-[13px]">
            <thead className="border-b border-line bg-canvas/40 text-[11px] font-medium text-ink-3">
              <tr>
                <th className="px-4 py-2.5 font-medium">任务</th>
                <th className="w-32 px-4 py-2.5 font-medium">状态</th>
                <th className="w-56 px-4 py-2.5 font-medium">进度</th>
                <th className="w-36 px-4 py-2.5 font-medium">速率 / ETA</th>
                <th className="w-36 px-4 py-2.5 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {tasks.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <EmptyState
                      icon={<CloudDownload className="h-5 w-5" />}
                      title="当前筛选下没有下载任务"
                      description={hasFilters ? '可以调整筛选条件或清除筛选后再看看。' : '新文件被扫描发现后会自动进入下载队列。'}
                      action={hasFilters ? <Button variant="soft" size="sm" onClick={clearFilters}>清除筛选</Button> : undefined}
                    />
                  </td>
                </tr>
              ) : (
                tasks.map((task) => {
                  const meta = TASK_STATE_META[task.state]
                  return (
                    <tr key={task.id} className="group transition-colors hover:bg-surface-2/35">
                      <td className="max-w-md px-4 py-3">
                        <TaskName task={task} sourceName={sourceMap.get(task.source_id) || task.source_id} hideFilenames={hideFilenames} />
                        <TaskError task={task} hideFilenames={hideFilenames} />
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <Badge tone={meta.tone}>
                            <StatusDot tone={meta.tone} pulse={task.state === 'RUNNING'} />
                            {meta.label}
                          </Badge>
                          {task.local_existing && <Badge tone="info">本地已存在</Badge>}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-between gap-2 whitespace-nowrap text-[10px] text-ink-3 tabular-nums">
                          <span>{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
                          <span className="shrink-0 text-ink-2">{task.progress_percent.toFixed(1)}%</span>
                        </div>
                        <ProgressBar value={task.progress_percent} barClassName={meta.progressClass} className="mt-1.5 h-1" />
                      </td>
                      <td className="px-4 py-3 tabular-nums">
                        <div className="whitespace-nowrap text-xs font-medium text-ink-2">{formatSpeed(task.speed_bytes_per_sec)}</div>
                        <div className="mt-0.5 whitespace-nowrap text-[11px] text-ink-3">
                          {task.state === 'COMPLETED' ? '已完成' : `ETA ${formatETA(task.eta_seconds)}`}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex justify-end">
                          <TaskActions task={task} onTaskAction={onTaskAction} onInspect={onInspect} />
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

      <div className="space-y-2.5 md:hidden">
        {tasks.length === 0 ? (
          <div className="rounded-xl border border-line bg-surface-1">
            <EmptyState
              icon={<CloudDownload className="h-5 w-5" />}
              title="当前筛选下没有下载任务"
              description={hasFilters ? '可以调整筛选条件或清除筛选。' : '新文件被扫描发现后会自动进入队列。'}
              action={hasFilters ? <Button variant="soft" size="sm" onClick={clearFilters}>清除筛选</Button> : undefined}
            />
          </div>
        ) : (
          tasks.map((task) => {
            const meta = TASK_STATE_META[task.state]
            return (
              <article key={task.id} className="rounded-xl border border-line bg-surface-1 p-3">
                <div className="flex items-start gap-2.5">
                  <StatusDot tone={meta.tone} pulse={task.state === 'RUNNING'} className="mt-1.5" />
                  <div className="min-w-0 flex-1">
                    <TaskName task={task} sourceName={sourceMap.get(task.source_id) || task.source_id} hideFilenames={hideFilenames} />
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-1">
                    <Badge tone={meta.tone}>{meta.label}</Badge>
                    {task.local_existing && <Badge tone="info">本地已存在</Badge>}
                  </div>
                </div>

                <TaskError task={task} hideFilenames={hideFilenames} />

                <div className="mt-3">
                  <ProgressBar value={task.progress_percent} barClassName={meta.progressClass} />
                  <div className="mt-1 flex items-center justify-between gap-2 text-[11px] text-ink-3 tabular-nums">
                    <span className="truncate">{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
                    <span className="shrink-0 text-ink-2">{task.progress_percent.toFixed(1)}%</span>
                  </div>
                </div>

                <div className="mt-3 flex items-center justify-between gap-2 border-t border-line pt-2.5">
                  <div className="min-w-0 text-[11px] text-ink-3 tabular-nums">
                    {task.state === 'COMPLETED' ? (
                      <span className="text-emerald-300/80">已完成</span>
                    ) : (
                      <>
                        <span className="font-medium text-ink-2">{formatSpeed(task.speed_bytes_per_sec)}</span>
                        <span className="mx-1.5 text-line-strong">·</span>
                        ETA {formatETA(task.eta_seconds)}
                      </>
                    )}
                  </div>
                  <TaskActions task={task} onTaskAction={onTaskAction} onInspect={onInspect} />
                </div>
              </article>
            )
          })
        )}
      </div>

      <Pagination total={total} limit={limit} offset={offset} onOffsetChange={onOffsetChange} />
    </div>
  )
}

function FieldLabel({ children }: { children: ReactNode }) {
  return <span className="text-[11px] font-medium text-ink-3">{children}</span>
}
