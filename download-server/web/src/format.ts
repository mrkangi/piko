import type { TaskState } from './types'

export function formatBytes(bytes: number | undefined | null): string {
  const num = Number(bytes || 0)
  if (num < 1024) return `${num} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let value = num / 1024
  let idx = 0
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024
    idx++
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[idx]}`
}

export function formatSpeed(bytesPerSec: number | undefined | null): string {
  if (!bytesPerSec || bytesPerSec <= 0) return '—'
  return `${formatBytes(bytesPerSec)}/s`
}

export function formatETA(seconds: number | undefined | null): string {
  if (seconds == null || seconds < 0) return '—'
  if (seconds < 60) return `${seconds}s`
  const mins = Math.floor(seconds / 60)
  if (mins < 60) return `${mins}m ${seconds % 60}s`
  const hours = Math.floor(mins / 60)
  return `${hours}h ${mins % 60}m`
}

export function formatTimestamp(unixSec: number | undefined | null): string {
  if (!unixSec) return '—'
  const d = new Date(unixSec * 1000)
  return d.toLocaleString('zh-CN', { hour12: false })
}

export function formatRelativeTime(unixSec: number | undefined | null): string {
  if (!unixSec) return '—'
  const diff = unixSec - Math.floor(Date.now() / 1000)
  const abs = Math.abs(diff)
  if (abs < 60) return diff >= 0 ? `${abs} 秒后` : `${abs} 秒前`
  const mins = Math.floor(abs / 60)
  if (mins < 60) return diff >= 0 ? `${mins} 分钟后` : `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  return diff >= 0 ? `${hours} 小时后` : `${hours} 小时前`
}

export type StatusTone = 'neutral' | 'active' | 'success' | 'wait' | 'danger' | 'info'

export const TASK_STATE_META: Record<
  TaskState,
  { label: string; tone: StatusTone; progressClass: string }
> = {
  QUEUED: { label: '排队中', tone: 'neutral', progressClass: 'bg-ink-3' },
  RUNNING: { label: '下载中', tone: 'active', progressClass: 'bg-accent' },
  PAUSING: { label: '暂停中', tone: 'wait', progressClass: 'bg-amber-400' },
  PAUSED: { label: '已暂停', tone: 'wait', progressClass: 'bg-amber-500' },
  CANCELING: { label: '取消中', tone: 'danger', progressClass: 'bg-rose-400' },
  CANCELED: { label: '已取消', tone: 'neutral', progressClass: 'bg-ink-3' },
  RETRY_WAIT: { label: '等待重试', tone: 'wait', progressClass: 'bg-amber-400' },
  VERIFYING: { label: '校验中', tone: 'info', progressClass: 'bg-cyan-400' },
  COMMITTING: { label: '交付中', tone: 'info', progressClass: 'bg-indigo-400' },
  COMPLETED: { label: '已完成', tone: 'success', progressClass: 'bg-emerald-500' },
  FAILED: { label: '失败', tone: 'danger', progressClass: 'bg-rose-500' },
  CONFLICT: { label: '路径冲突', tone: 'danger', progressClass: 'bg-rose-500' },
}
