import { AlertTriangle, ArrowLeft, ShieldCheck, X } from 'lucide-react'
import { useEffect } from 'react'
import type { ReactNode } from 'react'
import type { TaskView } from '../types'
import { TASK_STATE_META, formatBytes, formatETA, formatSpeed, formatTimestamp } from '../format'
import { maskPath, maskTextPaths } from '../privacy'
import { Badge, Button, ProgressBar, StatusDot } from './ui'

interface Props {
  task: TaskView | null
  hideFilenames: boolean
  sourceName: string
  onClose: () => void
  onAction: (id: string, action: 'pause' | 'resume' | 'cancel' | 'retry') => Promise<void>
}

export function TaskDetailDrawer({ task, hideFilenames, sourceName, onClose, onAction }: Props) {
  useEffect(() => {
    if (!task) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKey)
    }
  }, [task, onClose])

  if (!task) return null
  const meta = TASK_STATE_META[task.state]
  const targetPath = maskPath(task.target_rel_path, hideFilenames)
  const remotePath = maskPath(task.remote_path, hideFilenames)
  const stagingPath = maskPath(task.staging_rel_path, hideFilenames)
  const errorMessage = maskTextPaths(
    task.error_message,
    [task.target_rel_path, task.remote_path, task.staging_rel_path],
    hideFilenames,
  )

  return (
    <div className="fixed inset-0 z-50 flex bg-black/60 backdrop-blur-sm sm:justify-end" onMouseDown={onClose}>
      <div
        className="flex h-full w-full flex-col border-line bg-surface-1 shadow-2xl sm:max-w-[480px] sm:border-l"
        onMouseDown={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-start justify-between gap-3 border-b border-line px-4 py-3.5 sm:px-5">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5">
              <Badge tone={meta.tone}>
                <StatusDot tone={meta.tone} pulse={task.state === 'RUNNING'} />
                {meta.label}
              </Badge>
              {task.local_existing && <Badge tone="info">本地已存在</Badge>}
              <span className="text-[11px] text-ink-3">{sourceName}</span>
            </div>
            <h2 className="mt-1.5 truncate text-sm font-semibold text-ink" title={hideFilenames ? undefined : task.target_rel_path}>
              {targetPath}
            </h2>
          </div>
          <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="关闭" className="shrink-0">
            <X className="h-4 w-4 sm:hidden" />
            <ArrowLeft className="hidden h-4 w-4 sm:block" />
          </Button>
        </div>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4 sm:p-5">
          <div className="rounded-xl border border-line bg-canvas/40 p-3.5">
            <div className="flex items-center justify-between gap-2 text-[11px] text-ink-3">
              <span>进度 <strong className="font-medium text-ink-2 tabular-nums">{task.progress_percent.toFixed(1)}%</strong></span>
              <span className="tabular-nums">{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
            </div>
            <ProgressBar value={task.progress_percent} barClassName={meta.progressClass} className="mt-2" />
            <div className="mt-2 flex items-center justify-between text-[11px] text-ink-3 tabular-nums">
              <span>速度 <span className="text-ink-2">{formatSpeed(task.speed_bytes_per_sec)}</span></span>
              <span>剩余 <span className="text-ink-2">{formatETA(task.eta_seconds)}</span></span>
            </div>
          </div>

          <div className="flex items-start gap-2 rounded-xl border border-line bg-canvas/30 px-3 py-2.5 text-[11px] leading-4">
            {task.strong_version ? (
              <>
                <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-400" />
                <span className="text-ink-2">强内容版本（GCID + 字节长度），支持断点续传验证。</span>
              </>
            ) : (
              <>
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-400" />
                <span className="text-ink-2">无可靠 GCID，按长度校验并在中断时从零重下。</span>
              </>
            )}
          </div>

          {task.error_message && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/8 p-3 text-[11px] leading-5 text-rose-200">
              <div className="mb-1 font-medium">最近错误 ({task.error_code || 'error'})</div>
              <div className="break-all">{errorMessage}</div>
            </div>
          )}

          <dl className="space-y-2.5 text-[11px]">
            <InfoRow label="远端路径" value={remotePath} mono />
            <InfoRow label="远端对象 ID" value={task.remote_id} mono />
            <InfoRow label="任务 ID" value={task.id} mono />
            <InfoRow label="版本标识" value={task.version_key} mono />
            <InfoRow label="暂存路径" value={stagingPath} mono />
            <InfoRow label="尝试次数" value={`${task.attempt} / ${task.max_attempts}`} />
            <InfoRow label="创建时间" value={formatTimestamp(task.created_at)} />
            <InfoRow label="更新时间" value={formatTimestamp(task.updated_at)} />
            <InfoRow label="完成时间" value={formatTimestamp(task.finished_at)} />
          </dl>
        </div>

        <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line px-4 py-3 sm:px-5">
          {['QUEUED', 'RUNNING', 'RETRY_WAIT', 'VERIFYING'].includes(task.state) && (
            <Button variant="secondary" onClick={() => onAction(task.id, 'pause')}>
              暂停
            </Button>
          )}
          {task.state === 'PAUSED' && (
            <Button variant="primary" onClick={() => onAction(task.id, 'resume')}>
              继续下载
            </Button>
          )}
          {['FAILED', 'CANCELED', 'CONFLICT'].includes(task.state) && (
            <Button variant="primary" onClick={() => onAction(task.id, 'retry')}>
              重试
            </Button>
          )}
          {!['COMPLETED', 'CANCELED', 'CANCELING', 'COMMITTING'].includes(task.state) && (
            <Button variant="danger" onClick={() => onAction(task.id, 'cancel')}>
              取消任务
            </Button>
          )}
        </div>
      </div>
    </div>
  )
}

function InfoRow({ label, value, mono = false }: { label: string; value: ReactNode; mono?: boolean }) {
  return (
    <div className="grid grid-cols-[88px_minmax(0,1fr)] gap-2">
      <dt className="text-ink-3">{label}</dt>
      <dd className={mono ? 'break-all font-mono text-ink-2' : 'break-all text-ink-2'}>{value}</dd>
    </div>
  )
}
