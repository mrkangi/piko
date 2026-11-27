import {
  AlertTriangle,
  ArrowRight,
  ArrowUpCircle,
  CheckCircle2,
  CircleAlert,
  CloudDownload,
  FolderTree,
  HardDrive,
  Layers,
  RefreshCw,
  ScrollText,
  ShieldAlert,
} from 'lucide-react'
import type { ReactNode } from 'react'
import type { Source, SystemSettings, SystemStats, TaskView, UpdateInfo } from '../types'
import { formatBytes, formatRelativeTime, formatSpeed, formatTimestamp, TASK_STATE_META } from '../format'
import { maskPath } from '../privacy'
import { Badge, Button, Card, CardHeader, EmptyState, ProgressBar, StatusDot, cn } from './ui'
import { SpeedSparkline } from './SpeedSparkline'
import type { ManageSection } from './ManageTab'

interface Props {
  stats: SystemStats | null
  settings: SystemSettings | null
  sources: Source[]
  tasks: TaskView[]
  speedHistory: number[]
  updateInfo: UpdateInfo | null
  hideFilenames: boolean
  onShowTasks: (state?: string) => void
  onShowFiles: () => void
  onShowManage: (section?: ManageSection) => void
  onShowLogs: () => void
  onOpenUpdate: () => void
  onScanAll: () => void
  onInspect: (task: TaskView) => void
}

const ACTIVE_STATES = new Set(['RUNNING', 'VERIFYING', 'COMMITTING', 'PAUSING'])

function SourceHealth({ source, failureThreshold }: { source: Source; failureThreshold: number }) {
  const tone =
    source.last_scan_status === 'FAILED'
      ? 'danger'
      : source.last_scan_status === 'RETRYING'
        ? 'wait'
      : source.last_scan_status === 'RUNNING'
        ? 'active'
        : source.last_scan_status === 'SUCCEEDED'
          ? 'success'
          : 'neutral'
  return (
    <div className="flex items-center gap-2.5 px-4 py-2.5">
      <StatusDot tone={tone} pulse={source.last_scan_status === 'RUNNING'} />
      <div className="min-w-0 flex-1">
        <div className="truncate text-xs font-medium text-ink-2">{source.local_alias}</div>
        <div className="mt-0.5 truncate text-[10px] text-ink-3">
          {!source.enabled
            ? '已停用'
            : source.last_scan_status === 'RUNNING'
              ? '正在扫描'
              : source.last_scan_status === 'FAILED'
                ? '上次扫描失败'
                : source.last_scan_status === 'RETRYING'
                  ? `扫描暂时失败 ${source.consecutive_failures}/${failureThreshold}，等待重试`
                : `下次扫描 ${formatRelativeTime(source.next_scan_at)}`}
        </div>
      </div>
      <span className="shrink-0 text-[10px] text-ink-3">{source.initial_mode === 'monitor_new_only' ? '仅新增' : '含存量'}</span>
    </div>
  )
}

export function OverviewTab({
  stats,
  settings,
  sources,
  tasks,
  speedHistory,
  updateInfo,
  hideFilenames,
  onShowTasks,
  onShowFiles,
  onShowManage,
  onShowLogs,
  onOpenUpdate,
  onScanAll,
  onInspect,
}: Props) {
  const sourceMap = new Map(sources.map((source) => [source.id, source.local_alias]))
  const activeTasks = tasks.filter((task) => ACTIVE_STATES.has(task.state)).slice(0, 5)
  const recentTasks = tasks.filter((task) => task.state === 'COMPLETED').slice(0, 5)
  const failedSources = sources.filter((source) => source.enabled && source.last_scan_status === 'FAILED')
  const failedTasks = stats?.failed_count ?? 0
  const lowDisk = stats?.low_disk_blocked ?? false
  const updateReady = updateInfo?.available ?? false
  const hasAttention = lowDisk || failedTasks > 0 || failedSources.length > 0 || updateReady

  return (
    <div className="space-y-4 sm:space-y-5">
      <Card className="overflow-hidden">
        <div className="grid gap-5 p-4 sm:p-5 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-xs text-ink-3">
              <StatusDot tone={stats && stats.active_workers > 0 ? 'active' : 'neutral'} pulse={Boolean(stats && stats.active_workers > 0)} />
              实时总速度
            </div>
            <div className="mt-1.5 flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="text-3xl font-semibold tracking-tight tabular-nums sm:text-4xl">{formatSpeed(stats?.total_speed_bytes)}</span>
              <span className="text-xs text-ink-3">{stats?.active_workers ?? 0} 个活动下载</span>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => onShowTasks('QUEUED')}
                className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-line bg-surface-2/60 px-2.5 py-1.5 text-[11px] text-ink-2 transition-colors hover:border-line-strong hover:text-ink"
              >
                <Layers className="h-3.5 w-3.5 text-ink-3" />
                排队 <strong className="font-semibold text-ink tabular-nums">{stats?.queued_count ?? 0}</strong>
              </button>
              <button
                type="button"
                onClick={() => onShowTasks('PAUSED')}
                className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-line bg-surface-2/60 px-2.5 py-1.5 text-[11px] text-ink-2 transition-colors hover:border-line-strong hover:text-ink"
              >
                <StatusDot tone="wait" />
                暂停 <strong className="font-semibold text-ink tabular-nums">{stats?.paused_count ?? 0}</strong>
              </button>
              <button
                type="button"
                onClick={() => onShowTasks('COMPLETED')}
                className="flex cursor-pointer items-center gap-1.5 rounded-lg border border-line bg-surface-2/60 px-2.5 py-1.5 text-[11px] text-ink-2 transition-colors hover:border-line-strong hover:text-ink"
              >
                <StatusDot tone="success" />
                已交付 <strong className="font-semibold text-ink tabular-nums">{stats?.completed_count ?? 0}</strong>
              </button>
            </div>
          </div>
          <div className="hidden justify-self-end rounded-xl border border-line bg-canvas/40 px-4 py-3 md:block">
            <SpeedSparkline samples={speedHistory} width={220} height={56} />
          </div>
          <div className="md:hidden">
            <SpeedSparkline samples={speedHistory} width={320} height={44} className="w-full" />
          </div>
        </div>
      </Card>

      <div className="grid gap-4 sm:gap-5 lg:grid-cols-[minmax(0,1fr)_350px]">
        <div className="space-y-4 sm:space-y-5">
          <Card>
            <CardHeader
              title="需要注意"
              description={hasAttention ? '失败、空间与来源异常会集中显示在这里' : '当前没有需要处理的异常'}
              icon={hasAttention ? <AlertTriangle className="h-4 w-4 text-amber-400" /> : <CheckCircle2 className="h-4 w-4 text-emerald-400" />}
            />
            <div className="divide-y divide-line">
              {lowDisk && (
                <AttentionRow
                  tone="danger"
                  icon={<HardDrive className="h-4 w-4" />}
                  title="下载卷空间低于阈值"
                  description={`剩余 ${formatBytes(stats?.free_space_bytes)}，新任务派发已暂停。`}
                  action="查看存储设置"
                  onClick={() => onShowManage('storage')}
                />
              )}
              {failedTasks > 0 && (
                <AttentionRow
                  tone="danger"
                  icon={<ShieldAlert className="h-4 w-4" />}
                  title={`${failedTasks} 个任务失败或路径冲突`}
                  description="需要人工确认原因后重试，或调整本地路径。"
                  action="查看异常任务"
                  onClick={() => onShowTasks('FAILED')}
                />
              )}
              {failedSources.map((source) => (
                <AttentionRow
                  key={source.id}
                  tone="wait"
                  icon={<CircleAlert className="h-4 w-4" />}
                  title={`来源「${source.local_alias}」扫描失败`}
                  description={source.last_scan_error || '连续扫描失败，新文件不会被发现。'}
                  action="查看来源"
                  onClick={() => onShowManage('sources')}
                />
              ))}
              {updateReady && (
                <AttentionRow
                  tone="active"
                  icon={<ArrowUpCircle className="h-4 w-4" />}
                  title={`发现新版本 ${updateInfo?.latest_version || ''}`}
                  description="可直接在页面内下载、校验并重启升级。"
                  action="在线升级"
                  onClick={onOpenUpdate}
                />
              )}
              {!hasAttention && (
                <div className="flex items-center gap-3 px-4 py-6 text-xs text-ink-3 sm:px-5">
                  <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
                  服务运行正常，没有失败任务、空间告警或来源扫描异常。
                </div>
              )}
            </div>
          </Card>

          <Card>
            <CardHeader
              title="正在下载"
              description={activeTasks.length > 0 ? `显示前 ${activeTasks.length} 个活动任务` : '当前没有活动的下载任务'}
              icon={<CloudDownload className="h-4 w-4" />}
              actions={
                <Button variant="ghost" size="sm" onClick={() => onShowTasks('RUNNING')}>
                  查看全部 <ArrowRight className="h-3.5 w-3.5" />
                </Button>
              }
            />
            {activeTasks.length === 0 ? (
              <EmptyState
                icon={<CloudDownload className="h-5 w-5" />}
                title="下载队列空闲"
                description="添加监控目录或触发扫描后，新文件会自动进入队列。"
                action={<Button variant="soft" size="sm" onClick={onScanAll}>立即扫描全部来源</Button>}
              />
            ) : (
              <div className="divide-y divide-line">
                {activeTasks.map((task) => {
                  const meta = TASK_STATE_META[task.state]
                  return (
                    <button
                      key={task.id}
                      type="button"
                      onClick={() => onInspect(task)}
                      className="group block w-full cursor-pointer px-4 py-3 text-left transition-colors hover:bg-surface-2/50 sm:px-5"
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="truncate text-xs font-medium text-ink">
                            {maskPath(task.target_rel_path, hideFilenames)}
                          </div>
                          <div className="mt-1 flex items-center gap-1.5 text-[10px] text-ink-3">
                            <span className="truncate">{sourceMap.get(task.source_id) || task.source_id}</span>
                            <span>·</span>
                            <span className="truncate">{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
                          </div>
                        </div>
                        <div className="shrink-0 text-right">
                          <div className="text-xs font-medium text-ink tabular-nums">{task.progress_percent.toFixed(1)}%</div>
                          <div className="mt-0.5 text-[10px] text-ink-3 tabular-nums">{formatSpeed(task.speed_bytes_per_sec)}</div>
                        </div>
                      </div>
                      <div className="mt-2 flex items-center gap-2">
                        <ProgressBar value={task.progress_percent} barClassName={meta.progressClass} className="h-1" />
                        <span className="shrink-0 text-[10px] text-ink-3 tabular-nums">ETA {formatEtaShort(task.eta_seconds)}</span>
                      </div>
                    </button>
                  )
                })}
              </div>
            )}
          </Card>

          <Card>
            <CardHeader
              title="最近完成"
              icon={<CheckCircle2 className="h-4 w-4" />}
              actions={
                <Button variant="ghost" size="sm" onClick={() => onShowTasks('COMPLETED')}>
                  全部记录 <ArrowRight className="h-3.5 w-3.5" />
                </Button>
              }
            />
            {recentTasks.length === 0 ? (
              <div className="px-4 py-6 text-xs text-ink-3 sm:px-5">还没有已完成的下载记录。</div>
            ) : (
              <div className="divide-y divide-line">
                {recentTasks.map((task) => (
                  <button
                    key={task.id}
                    type="button"
                    onClick={() => onInspect(task)}
                    className="flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-surface-2/50 sm:px-5"
                  >
                    <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-emerald-400/80" />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-xs text-ink-2">{maskPath(task.target_rel_path, hideFilenames)}</div>
                      <div className="mt-0.5 text-[10px] text-ink-3">{formatTimestamp(task.finished_at)}</div>
                    </div>
                    <span className="shrink-0 text-[10px] text-ink-3 tabular-nums">{formatBytes(task.bytes_total)}</span>
                  </button>
                ))}
              </div>
            )}
          </Card>
        </div>

        <div className="space-y-4 sm:space-y-5">
          <Card>
            <CardHeader title="存储" icon={<HardDrive className="h-4 w-4" />} />
            <div className="px-4 py-4 sm:px-5">
              <div className="text-2xl font-semibold tabular-nums">{formatBytes(stats?.free_space_bytes)}</div>
              <div className="mt-1 text-xs text-ink-3">下载卷可用空间</div>
              <div className="mt-3 flex items-center gap-2">
                {lowDisk ? <Badge tone="danger">低于阈值</Badge> : <Badge tone="success">空间正常</Badge>}
                <span className="text-[11px] text-ink-3">最低保留 {settings?.min_free_space_mib ?? 0} MiB</span>
              </div>
              <div className="mt-3 rounded-lg border border-line bg-canvas/50 px-2.5 py-2 font-mono text-[11px] text-ink-3 break-all">
                {settings?.download_dir || '—'}
              </div>
            </div>
          </Card>

          <Card>
            <CardHeader
              title="监控来源"
              description={`${sources.filter((source) => source.enabled).length} 个启用 / ${sources.length} 个总计`}
              icon={<FolderTree className="h-4 w-4" />}
              actions={
                <Button variant="ghost" size="sm" onClick={() => onShowManage('sources')}>
                  管理
                </Button>
              }
            />
            {sources.length === 0 ? (
              <div className="px-4 py-5 text-xs text-ink-3 sm:px-5">尚未添加监控目录。</div>
            ) : (
              <div className="divide-y divide-line">
                {sources.slice(0, 5).map((source) => (
                  <SourceHealth key={source.id} source={source} failureThreshold={settings?.scan_failure_threshold ?? 3} />
                ))}
              </div>
            )}
            <div className="border-t border-line p-3">
              <Button variant="secondary" size="sm" onClick={onScanAll} className="w-full">
                <RefreshCw className="h-3.5 w-3.5" />
                扫描全部已启用来源
              </Button>
            </div>
          </Card>

          <Card>
            <CardHeader title="快捷入口" icon={<Layers className="h-4 w-4" />} />
            <div className="grid grid-cols-2 gap-2 p-3">
              <Button variant="secondary" size="sm" onClick={onShowFiles} className="justify-start">
                <FolderTree className="h-3.5 w-3.5 text-ink-3" />
                文件索引
              </Button>
              <Button variant="secondary" size="sm" onClick={onShowLogs} className="justify-start">
                <ScrollText className="h-3.5 w-3.5 text-ink-3" />
                运行日志
              </Button>
            </div>
          </Card>
        </div>
      </div>
    </div>
  )
}

function formatEtaShort(seconds: number | undefined | null): string {
  if (seconds == null || seconds < 0) return '—'
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`
}

function AttentionRow({
  tone,
  icon,
  title,
  description,
  action,
  onClick,
}: {
  tone: 'danger' | 'wait' | 'active'
  icon: ReactNode
  title: string
  description: string
  action: string
  onClick: () => void
}) {
  const toneClass =
    tone === 'danger'
      ? 'border-rose-500/20 bg-rose-500/6 text-rose-300'
      : tone === 'wait'
        ? 'border-amber-500/20 bg-amber-500/6 text-amber-300'
        : 'border-accent/20 bg-accent/6 text-accent'
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full cursor-pointer items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-surface-2/50 sm:px-5"
    >
      <span className={cn('mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border', toneClass)}>{icon}</span>
      <span className="min-w-0 flex-1">
        <span className="block text-xs font-medium text-ink-2">{title}</span>
        <span className="mt-0.5 line-clamp-2 block text-[11px] leading-4 text-ink-3" title={description}>{description}</span>
      </span>
      <span className="mt-1 inline-flex shrink-0 items-center gap-1 text-[11px] font-medium text-ink-3">
        <span className="hidden sm:inline">{action}</span>
        <ArrowRight className="h-3 w-3" />
      </span>
    </button>
  )
}
