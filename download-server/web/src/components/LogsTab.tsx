import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowDownToLine, ChevronUp, Pause, Play, RefreshCw, Search } from 'lucide-react'
import * as api from '../api'
import { formatBytes } from '../format'
import type { LogEntry, LogPage } from '../types'

const PAGE_LIMIT = 200
const MAX_RENDERED = 500
const POLL_INTERVAL_MS = 2000

function levelClass(text: string): string {
  const lower = text.toLowerCase()
  if (/(^|[^a-z])(fatal|panic|error|failed|failure)([^a-z]|$)/.test(lower) || text.includes('失败') || text.includes('错误')) {
    return 'text-rose-300'
  }
  if (/(^|[^a-z])(warn|warning|skip|unusable|retry)([^a-z]|$)/.test(lower) || text.includes('重试')) {
    return 'text-amber-300'
  }
  return 'text-slate-300'
}

function formatLogTime(milliseconds: number): string {
  const date = new Date(milliseconds)
  if (Number.isNaN(date.getTime())) return '—'
  const fraction = String(date.getMilliseconds()).padStart(3, '0')
  return `${date.toLocaleTimeString('zh-CN', { hour12: false })}.${fraction}`
}

export function LogsTab() {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [stats, setStats] = useState<LogPage | null>(null)
  const [query, setQuery] = useState('')
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [loading, setLoading] = useState(true)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [hasMoreBefore, setHasMoreBefore] = useState(false)
  const [olderCursor, setOlderCursor] = useState(0)
  const [notice, setNotice] = useState('')
  const [stickToBottom, setStickToBottom] = useState(true)

  const scrollRef = useRef<HTMLDivElement | null>(null)
  const lastSeqRef = useRef(0)
  const entriesRef = useRef<LogEntry[]>([])
  const loadingTailRef = useRef(false)

  useEffect(() => {
    entriesRef.current = entries
  }, [entries])

  useEffect(() => {
    if (entries.length > 0) {
      setOlderCursor(entries[0].seq)
    }
  }, [entries])

  const reloadTail = useCallback(async () => {
    if (loadingTailRef.current) return
    loadingTailRef.current = true
    setLoading(true)
    try {
      const page = await api.getLogs({ limit: PAGE_LIMIT })
      setEntries(page.entries)
      setStats(page)
      setHasMoreBefore(page.has_more_before)
      setOlderCursor(page.first_seq)
      lastSeqRef.current = page.last_seq
      setStickToBottom(true)
      setNotice(page.gap ? '部分旧日志已超出内存缓冲范围。' : '')
    } catch (err) {
      setNotice(err instanceof Error ? err.message : '日志加载失败')
    } finally {
      loadingTailRef.current = false
      setLoading(false)
    }
  }, [])

  const poll = useCallback(async () => {
    if (loadingTailRef.current) return
    try {
      const page = await api.getLogs({ after: lastSeqRef.current, limit: PAGE_LIMIT })
      setStats(page)
      if (page.gap) {
        setNotice('日志缓冲已滚动，正在重新载入最新窗口。')
        await reloadTail()
        return
      }
      setNotice('')
      lastSeqRef.current = Math.max(lastSeqRef.current, page.last_seq)
      if (page.entries.length === 0) return
      setEntries((prev) => {
        const seen = new Set(prev.map((entry) => entry.seq))
        const merged = [...prev, ...page.entries.filter((entry) => !seen.has(entry.seq))]
        return merged.length > MAX_RENDERED ? merged.slice(merged.length - MAX_RENDERED) : merged
      })
    } catch (err) {
      setNotice(err instanceof Error ? err.message : '日志刷新失败')
    }
  }, [reloadTail])

  const loadOlder = useCallback(async () => {
    if (!olderCursor || loadingOlder || !hasMoreBefore) return
    if (entriesRef.current.length >= MAX_RENDERED) {
      setNotice(`页面最多渲染 ${MAX_RENDERED} 条日志，请刷新以回到最新窗口。`)
      return
    }
    setLoadingOlder(true)
    try {
      const page = await api.getLogs({ before: olderCursor, limit: PAGE_LIMIT })
      if (page.entries.length === 0) {
        setHasMoreBefore(false)
        return
      }
      setEntries((prev) => {
        const seen = new Set(prev.map((entry) => entry.seq))
        const older = page.entries.filter((entry) => !seen.has(entry.seq))
        const room = Math.max(0, MAX_RENDERED - prev.length)
        if (room === 0) return prev
        return [...older.slice(Math.max(0, older.length - room)), ...prev]
      })
      setOlderCursor(page.first_seq)
      setHasMoreBefore(page.has_more_before)
    } catch (err) {
      setNotice(err instanceof Error ? err.message : '旧日志加载失败')
    } finally {
      setLoadingOlder(false)
    }
  }, [hasMoreBefore, loadingOlder, olderCursor])

  useEffect(() => {
    void reloadTail()
  }, [reloadTail])

  useEffect(() => {
    if (!autoRefresh) return
    const id = window.setInterval(() => {
      void poll()
    }, POLL_INTERVAL_MS)
    return () => window.clearInterval(id)
  }, [autoRefresh, poll])

  useEffect(() => {
    if (!stickToBottom) return
    const el = scrollRef.current
    if (!el) return
    const frame = window.requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight
    })
    return () => window.cancelAnimationFrame(frame)
  }, [entries, stickToBottom])

  const visibleEntries = useMemo(() => {
    const needle = query.trim().toLowerCase()
    if (!needle) return entries
    return entries.filter((entry) => entry.text.toLowerCase().includes(needle))
  }, [entries, query])

  function onScroll() {
    const el = scrollRef.current
    if (!el) return
    setStickToBottom(el.scrollHeight - el.scrollTop - el.clientHeight < 64)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 xl:flex-row xl:items-center xl:justify-between">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative w-full sm:w-72">
            <Search className="absolute left-3 top-2.5 h-3.5 w-3.5 text-slate-500" />
            <input
              type="text"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索已加载日志…"
              className="w-full rounded-lg border border-slate-800 bg-slate-900 pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:border-blue-500 focus:outline-none"
            />
          </div>
          <button
            onClick={() => setAutoRefresh((value) => !value)}
            className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs cursor-pointer ${
              autoRefresh
                ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                : 'border-slate-800 bg-slate-900 text-slate-300 hover:bg-slate-800'
            }`}
          >
            {autoRefresh ? <Pause className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
            {autoRefresh ? '自动刷新中' : '自动刷新已暂停'}
          </button>
          <button
            onClick={() => void reloadTail()}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800 disabled:opacity-50 cursor-pointer"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </button>
          <button
            onClick={() => void loadOlder()}
            disabled={!hasMoreBefore || loadingOlder || entries.length >= MAX_RENDERED}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 hover:bg-slate-800 disabled:opacity-40 cursor-pointer"
          >
            <ChevronUp className="h-3.5 w-3.5" />
            {loadingOlder ? '加载中…' : '加载更早'}
          </button>
          {!stickToBottom && (
            <button
              onClick={() => setStickToBottom(true)}
              className="inline-flex items-center gap-1.5 rounded-lg border border-blue-500/40 bg-blue-600/15 px-3 py-1.5 text-xs text-blue-300 hover:bg-blue-600/25 cursor-pointer"
            >
              <ArrowDownToLine className="h-3.5 w-3.5" />
              回到最新
            </button>
          )}
        </div>

        <div className="text-xs text-slate-400">
          {stats ? (
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
              <span>
                后端缓冲 {stats.buffered} 条 / {formatBytes(stats.bytes)}（上限 {stats.max_entries} 条或 {formatBytes(stats.max_bytes)}）
              </span>
              <span>已淘汰 {stats.dropped} 条</span>
              <span className="text-slate-500">页面渲染 {entries.length} / {MAX_RENDERED} 条</span>
            </div>
          ) : (
            <span>正在读取日志缓冲…</span>
          )}
        </div>
      </div>

      {notice && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-200">
          {notice}
        </div>
      )}

      <div className="overflow-hidden rounded-2xl border border-slate-800 bg-slate-950/80">
        <div className="flex items-center justify-between border-b border-slate-800 bg-slate-900/70 px-4 py-2.5 text-[11px] text-slate-400">
          <span>最近日志（仅内存保留，服务重启后清空）</span>
          <span className="tabular-nums">匹配 {visibleEntries.length} 条</span>
        </div>
        <div
          ref={scrollRef}
          onScroll={onScroll}
          role="log"
          aria-live="off"
          className="h-[58vh] min-h-[320px] overflow-y-auto px-2 py-2 font-mono text-[11.5px] leading-5"
        >
          {loading && entries.length === 0 ? (
            <div className="px-3 py-10 text-center text-xs text-slate-500">正在加载日志…</div>
          ) : visibleEntries.length === 0 ? (
            <div className="px-3 py-10 text-center text-xs text-slate-500">
              {entries.length === 0 ? '当前还没有日志。' : '没有匹配的日志行。'}
            </div>
          ) : (
            visibleEntries.map((entry) => (
              <div key={entry.seq} className="flex gap-3 rounded px-2 py-0.5 hover:bg-slate-800/40">
                <span className="w-24 shrink-0 text-slate-500 tabular-nums">{formatLogTime(entry.time)}</span>
                <span className={`min-w-0 whitespace-pre-wrap break-all ${levelClass(entry.text)}`}>{entry.text}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
