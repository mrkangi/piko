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

export const TASK_STATE_META: Record<
  TaskState,
  { label: string; badgeClass: string; progressClass: string }
> = {
  QUEUED: {
    label: '排队中',
    badgeClass: 'bg-slate-500/15 text-slate-300 border-slate-500/30',
    progressClass: 'bg-slate-400',
  },
  RUNNING: {
    label: '下载中',
    badgeClass: 'bg-blue-500/15 text-blue-300 border-blue-500/30',
    progressClass: 'bg-blue-500',
  },
  PAUSING: {
    label: '暂停中',
    badgeClass: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    progressClass: 'bg-amber-400',
  },
  PAUSED: {
    label: '已暂停',
    badgeClass: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    progressClass: 'bg-amber-500',
  },
  CANCELING: {
    label: '取消中',
    badgeClass: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
    progressClass: 'bg-rose-400',
  },
  CANCELED: {
    label: '已取消',
    badgeClass: 'bg-slate-500/15 text-slate-400 border-slate-600/40',
    progressClass: 'bg-slate-500',
  },
  RETRY_WAIT: {
    label: '等待重试',
    badgeClass: 'bg-orange-500/15 text-orange-300 border-orange-500/30',
    progressClass: 'bg-orange-400',
  },
  VERIFYING: {
    label: '校验中',
    badgeClass: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/30',
    progressClass: 'bg-cyan-400',
  },
  COMMITTING: {
    label: '交付中',
    badgeClass: 'bg-indigo-500/15 text-indigo-300 border-indigo-500/30',
    progressClass: 'bg-indigo-400',
  },
  COMPLETED: {
    label: '已完成',
    badgeClass: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
    progressClass: 'bg-emerald-500',
  },
  FAILED: {
    label: '失败',
    badgeClass: 'bg-rose-500/15 text-rose-300 border-rose-500/30',
    progressClass: 'bg-rose-500',
  },
  CONFLICT: {
    label: '路径冲突',
    badgeClass: 'bg-red-500/20 text-red-300 border-red-500/40',
    progressClass: 'bg-red-500',
  },
}
