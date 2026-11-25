import { X, ShieldCheck, AlertTriangle } from 'lucide-react'
import type { TaskView } from '../types'
import { TASK_STATE_META, formatBytes, formatETA, formatSpeed, formatTimestamp } from '../format'
import { maskPath, maskTextPaths } from '../privacy'

interface Props {
  task: TaskView | null
  hideFilenames: boolean
  sourceName: string
  onClose: () => void
  onAction: (id: string, action: 'pause' | 'resume' | 'cancel' | 'retry') => Promise<void>
}

export function TaskDetailDrawer({ task, hideFilenames, sourceName, onClose, onAction }: Props) {
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
    <div className="fixed inset-0 z-50 flex justify-end bg-black/60 backdrop-blur-xs" onClick={onClose}>
      <div
        className="flex h-full w-full max-w-lg flex-col border-l border-slate-800 bg-slate-900 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-800 px-6 py-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs font-medium ${meta.badgeClass}`}>
                {meta.label}
              </span>
              {task.local_existing && (
                <span className="inline-flex items-center rounded-full border border-cyan-500/30 bg-cyan-500/15 px-2 py-0.5 text-[11px] text-cyan-300">
                  本地已存在
                </span>
              )}
              <span className="text-xs text-slate-400">{sourceName}</span>
            </div>
            <h3 className="mt-1 truncate text-base font-semibold text-slate-100">{targetPath}</h3>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 cursor-pointer">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-6 space-y-5 text-sm">
          <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-2">
            <div className="flex justify-between text-xs text-slate-400">
              <span>进度 {task.progress_percent.toFixed(1)}%</span>
              <span className="tabular-nums">{formatBytes(task.bytes_done)} / {formatBytes(task.bytes_total)}</span>
            </div>
            <div className="h-2 w-full overflow-hidden rounded-full bg-slate-800">
              <div className={`h-full transition-all ${meta.progressClass}`} style={{ width: `${task.progress_percent}%` }} />
            </div>
            <div className="flex justify-between text-xs text-slate-400 pt-1 tabular-nums">
              <span>速度：{formatSpeed(task.speed_bytes_per_sec)}</span>
              <span>剩余：{formatETA(task.eta_seconds)}</span>
            </div>
          </div>

          <div className="flex items-center gap-2 rounded-xl border border-slate-800 bg-slate-950/40 px-4 py-3 text-xs">
            {task.strong_version ? (
              <>
                <ShieldCheck className="h-4 w-4 text-emerald-400 shrink-0" />
                <span className="text-slate-300">强内容版本（GCID + 字节长度），支持断点续传验证</span>
              </>
            ) : (
              <>
                <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0" />
                <span className="text-slate-300">无可靠 GCID，按长度校验并在中断时从零重下</span>
              </>
            )}
          </div>

          {task.error_message && (
            <div className="rounded-xl border border-rose-500/40 bg-rose-500/10 p-3.5 text-xs text-rose-200">
              <div className="font-semibold mb-1">最近错误 ({task.error_code || 'error'})</div>
              <div className="break-all">{errorMessage}</div>
            </div>
          )}

          <dl className="grid grid-cols-[110px_1fr] gap-y-3 gap-x-4 text-xs">
            <dt className="text-slate-400">任务 ID</dt>
            <dd className="font-mono text-slate-200 break-all">{task.id}</dd>
            <dt className="text-slate-400">远端路径</dt>
            <dd className="text-slate-200 break-all">{remotePath}</dd>
            <dt className="text-slate-400">远端对象 ID</dt>
            <dd className="font-mono text-slate-200 break-all">{task.remote_id}</dd>
            <dt className="text-slate-400">版本标识</dt>
            <dd className="font-mono text-slate-300 break-all">{task.version_key}</dd>
            <dt className="text-slate-400">暂存路径</dt>
            <dd className="font-mono text-slate-300 break-all">{stagingPath}</dd>
            <dt className="text-slate-400">尝试次数</dt>
            <dd className="tabular-nums text-slate-200">{task.attempt} / {task.max_attempts}</dd>
            <dt className="text-slate-400">创建时间</dt>
            <dd className="tabular-nums text-slate-300">{formatTimestamp(task.created_at)}</dd>
            <dt className="text-slate-400">更新时间</dt>
            <dd className="tabular-nums text-slate-300">{formatTimestamp(task.updated_at)}</dd>
            <dt className="text-slate-400">完成时间</dt>
            <dd className="tabular-nums text-slate-300">{formatTimestamp(task.finished_at)}</dd>
          </dl>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-800 px-6 py-4">
          {['QUEUED', 'RUNNING', 'RETRY_WAIT', 'VERIFYING'].includes(task.state) && (
            <button onClick={() => onAction(task.id, 'pause')} className="rounded-lg border border-slate-700 bg-slate-800 px-3.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer">
              暂停
            </button>
          )}
          {task.state === 'PAUSED' && (
            <button onClick={() => onAction(task.id, 'resume')} className="rounded-lg bg-blue-600 px-3.5 py-1.5 text-xs font-medium text-white hover:bg-blue-500 cursor-pointer">
              继续下载
            </button>
          )}
          {['FAILED', 'CANCELED', 'CONFLICT'].includes(task.state) && (
            <button onClick={() => onAction(task.id, 'retry')} className="rounded-lg bg-blue-600 px-3.5 py-1.5 text-xs font-medium text-white hover:bg-blue-500 cursor-pointer">
              重试
            </button>
          )}
          {!['COMPLETED', 'CANCELED', 'CANCELING', 'COMMITTING'].includes(task.state) && (
            <button onClick={() => onAction(task.id, 'cancel')} className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3.5 py-1.5 text-xs text-rose-300 hover:bg-rose-500/20 cursor-pointer">
              取消任务
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
