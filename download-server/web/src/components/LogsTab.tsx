import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ArrowDownToLine, ChevronUp, Download, Pause, Play, RefreshCw, Search } from 'lucide-react'
import * as api from '../api'
import { formatBytes } from '../format'
import type { LogEntry, LogPage } from '../types'
import { Badge, Button, Card, Segmented, cn, inputClassName } from './ui'

const PAGE_LIMIT = 200
const MAX_RENDERED = 500
const POLL_INTERVAL_MS = 2000

type LevelFilter = '' | 'warn' | 'error'

function severity(text: string): number {
  const lower = text.toLowerCase()
  if (/(^|[^a-z])(fatal|panic|error|failed|failure)([^a-z]|$)/.test(lower) || text.includes('失败') || text.includes('错误')) {
    return 2
  }
  if (/(^|[^a-z])(warn|warning|skip|unusable|retry)([^a-z]|$)/.test(lower) || text.includes('重试')) {
    return 1
  }
  return 0
}

function levelClass(text: string): string {
  const level = severity(text)
  if (level === 2) return 'text-rose-300'
  if (level === 1) return 'text-amber-300'
  return 'text-ink-2'
}

function formatLogTime(milliseconds: number): string {
  const date = new Date(milliseconds)
  if (Number.isNaN(date.getTime())) return '—'
  const fraction = String(date.getMilliseconds()).padStart(3, '0')
  return `${date.toLocaleTimeString('zh-CN', { hour12: false })}.${fraction}`
}

function fileStamp(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
}

export function LogsTab() {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [stats, setStats] = useState<LogPage | null>(null)
  const [query, setQuery] = useState('')
  const [level, setLevel] = useState<LevelFilter>('')
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
    const element = scrollRef.current
    if (!element) return
    const frame = window.requestAnimationFrame(() => {
      element.scrollTop = element.scrollHeight
    })
    return () => window.cancelAnimationFrame(frame)
  }, [entries, stickToBottom])

  const visibleEntries = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const minimum = level === 'error' ? 2 : level === 'warn' ? 1 : 0
    return entries.filter((entry) => {
      if (severity(entry.text) < minimum) return false
      if (needle && !entry.text.toLowerCase().includes(needle)) return false
      return true
    })
  }, [entries, level, query])

  const matchCounts = useMemo(() => {
    let warnings = 0
    let errors = 0
    for (const entry of entries) {
      const value = severity(entry.text)
      if (value === 2) errors++
      else if (value === 1) warnings++
    }
    return { warnings, errors }
  }, [entries])

  function onScroll() {
    const element = scrollRef.current
    if (!element) return
    setStickToBottom(element.scrollHeight - element.scrollTop - element.clientHeight < 64)
  }

  function exportLogs() {
    const text = visibleEntries.map((entry) => `${formatLogTime(entry.time)} ${entry.text}`).join('\n')
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `piko-download-server-logs-${fileStamp()}.txt`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2.5 xl:flex-row xl:items-center xl:justify-between">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <Segmented
            value={level}
            options={[
              { key: '', label: '全部', count: entries.length },
              { key: 'warn', label: '警告', count: matchCounts.warnings },
              { key: 'error', label: '错误', count: matchCounts.errors },
            ]}
            onChange={setLevel}
          />
          <div className="relative min-w-0 flex-1 sm:w-64 sm:flex-none">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-3" />
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索已加载日志…"
              className={cn(inputClassName, 'h-9 py-0 pl-8 text-xs')}
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:items-center">
          <Button
            variant={autoRefresh ? 'soft' : 'secondary'}
            size="sm"
            onClick={() => setAutoRefresh((value) => !value)}
            className="justify-center"
          >
            {autoRefresh ? <Pause className="h-3.5 w-3.5" /> : <Play className="h-3.5 w-3.5" />}
            {autoRefresh ? '自动刷新中' : '已暂停刷新'}
          </Button>
          <Button variant="secondary" size="sm" onClick={() => void reloadTail()} loading={loading} className="justify-center">
            <RefreshCw className="h-3.5 w-3.5" />
            刷新
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => void loadOlder()}
            disabled={!hasMoreBefore || loadingOlder || entries.length >= MAX_RENDERED}
            className="justify-center"
          >
            <ChevronUp className="h-3.5 w-3.5" />
            {loadingOlder ? '加载中…' : '更早'}
          </Button>
          <Button variant="secondary" size="sm" onClick={exportLogs} disabled={visibleEntries.length === 0} className="justify-center">
            <Download className="h-3.5 w-3.5" />
            导出
          </Button>
          {!stickToBottom && (
            <Button variant="soft" size="sm" onClick={() => setStickToBottom(true)} className="col-span-2 justify-center sm:col-span-1">
              <ArrowDownToLine className="h-3.5 w-3.5" />
              回到最新
            </Button>
          )}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-[11px] text-ink-3">
        {stats ? (
          <>
            <Badge tone="neutral">缓冲 {stats.buffered} 条</Badge>
            <Badge tone="neutral">{formatBytes(stats.bytes)}</Badge>
            <span className="hidden sm:inline">上限 {stats.max_entries} 条或 {formatBytes(stats.max_bytes)}</span>
            <span className="hidden sm:inline">已淘汰 {stats.dropped} 条</span>
            <span>页面 {entries.length} / {MAX_RENDERED} 条</span>
          </>
        ) : (
          <span>正在读取日志缓冲…</span>
        )}
      </div>

      {notice && <div className="rounded-lg border border-amber-500/25 bg-amber-500/8 px-3 py-2 text-[11px] text-amber-200">{notice}</div>}

      <Card className="overflow-hidden">
        <div className="flex items-center justify-between gap-2 border-b border-line bg-canvas/30 px-3 py-2 text-[11px] text-ink-3 sm:px-4">
          <span className="flex items-center gap-2">
            <span className={cn('h-1.5 w-1.5 rounded-full', autoRefresh ? 'bg-emerald-400' : 'bg-ink-3')} />
            {autoRefresh ? '实时跟随最新日志' : '自动刷新已暂停'}
          </span>
          <span className="tabular-nums">匹配 {visibleEntries.length} 条</span>
        </div>
        <div
          ref={scrollRef}
          onScroll={onScroll}
          role="log"
          aria-live="off"
          className="h-[58vh] min-h-[320px] overflow-y-auto px-1.5 py-1.5 font-mono text-[11.5px] leading-5"
        >
          {loading && entries.length === 0 ? (
            <div className="px-3 py-10 text-center text-xs text-ink-3">正在加载日志…</div>
          ) : visibleEntries.length === 0 ? (
            <div className="px-3 py-10 text-center text-xs text-ink-3">
              {entries.length === 0 ? '当前还没有日志。' : '没有匹配的日志行。'}
            </div>
          ) : (
            visibleEntries.map((entry) => (
              <div
                key={entry.seq}
                className="flex flex-col gap-0.5 rounded px-2 py-1 transition-colors hover:bg-surface-2/50 sm:flex-row sm:gap-3 sm:py-0.5"
              >
                <span className="shrink-0 text-[10px] text-ink-3 tabular-nums sm:w-[92px] sm:text-[11px]">{formatLogTime(entry.time)}</span>
                <span className={cn('min-w-0 break-words whitespace-pre-wrap', levelClass(entry.text))}>{entry.text}</span>
              </div>
            ))
          )}
        </div>
      </Card>
    </div>
  )
}
