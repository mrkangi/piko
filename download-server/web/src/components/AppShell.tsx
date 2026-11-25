import { useEffect, useRef, useState, type ReactNode } from 'react'
import {
  ArrowUpCircle,
  CloudDownload,
  Eye,
  EyeOff,
  FolderTree,
  LayoutDashboard,
  LogOut,
  MoreHorizontal,
  RefreshCw,
  ScrollText,
  Settings2,
  ShieldCheck,
  Wifi,
  WifiOff,
} from 'lucide-react'
import type { AuthUser } from '../types'
import { Badge, Button, StatusDot, cn } from './ui'

export type TabKey = 'overview' | 'tasks' | 'files' | 'logs' | 'manage'
export type SseState = 'connecting' | 'connected' | 'reconnecting'

const TAB_META: Record<TabKey, { label: string; icon: typeof LayoutDashboard; description: string }> = {
  overview: { label: '概览', icon: LayoutDashboard, description: '运行状态与异常汇总' },
  tasks: { label: '下载任务', icon: CloudDownload, description: '进度、速度与任务控制' },
  files: { label: '文件索引', icon: FolderTree, description: '远端发现与本地交付记录' },
  logs: { label: '运行日志', icon: ScrollText, description: '内存日志与问题排查' },
  manage: { label: '管理设置', icon: Settings2, description: '存储、来源、账号、通知与升级' },
}

interface ShellProps {
  tab: TabKey
  onTab: (tab: TabKey) => void
  user: AuthUser
  sseState: SseState
  version?: string
  updateAvailable: boolean
  hideFilenames: boolean
  taskCount: number
  activeTaskCount: number
  fileCount: number
  onToggleFilenames: () => void
  onRefresh: () => void
  onOpenLogs: () => void
  onOpenUpdate: () => void
  onLogout: () => void
  children: ReactNode
}

function useDismiss(open: boolean, close: () => void) {
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!open) return
    const onPointer = (event: MouseEvent) => {
      if (!ref.current?.contains(event.target as Node)) close()
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close()
    }
    window.addEventListener('mousedown', onPointer)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onPointer)
      window.removeEventListener('keydown', onKey)
    }
  }, [open, close])
  return ref
}

function ConnectionPill({ state }: { state: SseState }) {
  const connected = state === 'connected'
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md border px-2 py-1 text-[11px] font-medium',
        connected ? 'border-emerald-500/25 bg-emerald-500/8 text-emerald-300' : 'border-amber-500/25 bg-amber-500/8 text-amber-300',
      )}
    >
      <StatusDot tone={connected ? 'success' : 'wait'} pulse={!connected} />
      {connected ? '实时连接' : state === 'connecting' ? '连接中' : '重连中'}
    </span>
  )
}

export function AppShell({
  tab,
  onTab,
  user,
  sseState,
  version,
  updateAvailable,
  hideFilenames,
  taskCount,
  activeTaskCount,
  fileCount,
  onToggleFilenames,
  onRefresh,
  onOpenLogs,
  onOpenUpdate,
  onLogout,
  children,
}: ShellProps) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useDismiss(menuOpen, () => setMenuOpen(false))
  const meta = TAB_META[tab]

  const primary = [
    { key: 'overview' as const, count: null },
    { key: 'tasks' as const, count: taskCount },
    { key: 'files' as const, count: fileCount },
    { key: 'manage' as const, count: null },
  ]

  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-[232px] flex-col border-r border-line bg-surface-1/70 backdrop-blur lg:flex">
        <div className="flex h-14 items-center gap-2.5 px-5">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg border border-accent/30 bg-accent/12 text-accent">
            <CloudDownload className="h-[18px] w-[18px]" />
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold tracking-tight">Piko Download</div>
            <div className="truncate text-[10px] text-ink-3">self-hosted server</div>
          </div>
        </div>

        <nav className="flex-1 space-y-1 px-3 py-4" aria-label="主导航">
          {primary.map((item) => {
            const itemMeta = TAB_META[item.key]
            const Icon = itemMeta.icon
            const active = tab === item.key
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => onTab(item.key)}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'group relative flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors',
                  active ? 'bg-accent/12 text-ink' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
                )}
              >
                {active && <span className="absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-accent" />}
                <Icon className={cn('h-4 w-4 shrink-0', active ? 'text-accent' : 'text-ink-3 group-hover:text-ink-2')} />
                <span className="flex-1 truncate text-left">{itemMeta.label}</span>
                {item.count != null && item.count > 0 && (
                  <span className="rounded bg-surface-3 px-1.5 py-0.5 text-[10px] tabular-nums text-ink-2">{item.count}</span>
                )}
              </button>
            )
          })}

          <div className="my-2 border-t border-line" />

          <button
            type="button"
            onClick={onOpenLogs}
            className={cn(
              'group flex w-full cursor-pointer items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors',
              tab === 'logs' ? 'bg-surface-2 text-ink' : 'text-ink-3 hover:bg-surface-2 hover:text-ink-2',
            )}
          >
            <ScrollText className="h-4 w-4 shrink-0" />
            <span className="flex-1 text-left">运行日志</span>
          </button>
        </nav>

        <div className="space-y-3 border-t border-line px-3 py-3">
          <div className="flex items-center justify-between rounded-lg border border-line bg-canvas/40 px-2.5 py-2">
            <ConnectionPill state={sseState} />
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={onOpenUpdate}
              title={`当前版本 ${version || '未知'}`}
              className="font-mono text-[10px] text-ink-3"
            >
              {updateAvailable ? <ArrowUpCircle className="h-3.5 w-3.5 text-accent" /> : <span className="max-w-16 truncate">v{version || '…'}</span>}
            </Button>
          </div>
          <div className="flex items-center gap-2 px-1">
            <div className="flex h-7 w-7 items-center justify-center rounded-full border border-line bg-surface-2 text-[11px] font-semibold uppercase text-ink-2">
              {user.username.slice(0, 1)}
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-xs font-medium text-ink-2">{user.username}</div>
              <div className="text-[10px] text-ink-3">管理员</div>
            </div>
            <Button variant="ghost" size="icon-sm" onClick={onLogout} title="退出登录" aria-label="退出登录">
              <LogOut className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </aside>

      <div className="lg:pl-[232px]">
        <header className="sticky top-0 z-30 border-b border-line bg-canvas/85 backdrop-blur-md">
          <div className="mx-auto flex h-14 max-w-[1280px] items-center justify-between gap-3 px-4 sm:px-6">
            <div className="flex min-w-0 items-center gap-2.5 lg:hidden">
              <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-accent/30 bg-accent/12 text-accent">
                <CloudDownload className="h-4 w-4" />
              </div>
              <div className="min-w-0">
                <div className="truncate text-sm font-semibold">{meta.label}</div>
                <div className="truncate text-[10px] text-ink-3">Piko Download Server</div>
              </div>
            </div>

            <div className="hidden min-w-0 items-center gap-2 lg:flex">
              <span className="text-xs text-ink-3">Piko Download Server</span>
              <span className="text-ink-3/60">/</span>
              <span className="text-sm font-semibold text-ink">{meta.label}</span>
              <span className="hidden truncate text-xs text-ink-3 xl:inline">· {meta.description}</span>
            </div>

            <div className="flex items-center gap-1.5">
              <div className="hidden sm:block">
                <ConnectionPill state={sseState} />
              </div>
              <div className="sm:hidden">
                <StatusDot tone={sseState === 'connected' ? 'success' : 'wait'} pulse={sseState !== 'connected'} className="mx-1" />
              </div>
              <Button variant="ghost" size="icon-sm" onClick={onRefresh} title="刷新" aria-label="刷新">
                <RefreshCw className="h-4 w-4" />
              </Button>
              <Button variant="ghost" size="icon-sm" onClick={onOpenLogs} title="运行日志" aria-label="运行日志" className="max-sm:hidden">
                <ScrollText className="h-4 w-4" />
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={onOpenUpdate}
                title={`当前版本 ${version || '未知'}${updateAvailable ? '，有新版本' : ''}`}
                aria-label="版本与升级"
                className="relative max-sm:hidden"
              >
                <ShieldCheck className="h-4 w-4" />
                {updateAvailable && <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-accent" />}
              </Button>
              <div ref={menuRef} className="relative">
                <Button
                  variant="ghost"
                  size="icon-sm"
                  onClick={() => setMenuOpen((value) => !value)}
                  aria-label="更多操作"
                  aria-expanded={menuOpen}
                  className={cn(menuOpen && 'bg-surface-2 text-ink')}
                >
                  <MoreHorizontal className="h-4 w-4" />
                </Button>
                {menuOpen && (
                  <div className="absolute right-0 top-10 z-40 w-56 overflow-hidden rounded-xl border border-line bg-surface-1 py-1 shadow-2xl">
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        onToggleFilenames()
                      }}
                      className="flex w-full cursor-pointer items-center gap-2.5 px-3 py-2 text-xs text-ink-2 hover:bg-surface-2 hover:text-ink"
                    >
                      {hideFilenames ? <EyeOff className="h-3.5 w-3.5 text-amber-400" /> : <Eye className="h-3.5 w-3.5" />}
                      {hideFilenames ? '显示明文文件名' : '隐藏文件名'}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        onOpenLogs()
                      }}
                      className="flex w-full cursor-pointer items-center gap-2.5 px-3 py-2 text-xs text-ink-2 hover:bg-surface-2 hover:text-ink"
                    >
                      <ScrollText className="h-3.5 w-3.5" />
                      运行日志
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        onOpenUpdate()
                      }}
                      className="flex w-full cursor-pointer items-center gap-2.5 px-3 py-2 text-xs text-ink-2 hover:bg-surface-2 hover:text-ink"
                    >
                      <ArrowUpCircle className="h-3.5 w-3.5" />
                      <span className="flex-1 text-left">版本与在线升级</span>
                      {updateAvailable && <Badge tone="active">可升级</Badge>}
                    </button>
                    <div className="my-1 border-t border-line" />
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        onLogout()
                      }}
                      className="flex w-full cursor-pointer items-center gap-2.5 px-3 py-2 text-xs text-ink-2 hover:bg-surface-2 hover:text-ink"
                    >
                      <LogOut className="h-3.5 w-3.5" />
                      退出登录
                    </button>
                  </div>
                )}
              </div>
            </div>
          </div>
          {sseState === 'reconnecting' && (
            <div className="flex items-center gap-2 border-t border-amber-500/20 bg-amber-500/8 px-4 py-1.5 text-[11px] text-amber-300 sm:px-6">
              <WifiOff className="h-3.5 w-3.5" />
              实时连接已断开，正在重连；页面数据可能短暂滞后。
            </div>
          )}
          {sseState === 'connected' && tab === 'overview' && (
            <div className="pointer-events-none absolute inset-x-0 -bottom-px h-px bg-gradient-to-r from-transparent via-accent/30 to-transparent" />
          )}
        </header>

        <main className="mx-auto w-full max-w-[1280px] px-4 pb-24 pt-4 sm:px-6 sm:pt-6 lg:pb-10">{children}</main>
      </div>

      <nav
        className="safe-bottom fixed inset-x-0 bottom-0 z-30 border-t border-line bg-canvas/95 backdrop-blur-md lg:hidden"
        aria-label="底部导航"
      >
        <div className="mx-auto grid max-w-lg grid-cols-4 px-2">
          {primary.map((item) => {
            const itemMeta = TAB_META[item.key]
            const Icon = itemMeta.icon
            const active = tab === item.key
            return (
              <button
                key={item.key}
                type="button"
                onClick={() => onTab(item.key)}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'relative flex h-14 cursor-pointer flex-col items-center justify-center gap-0.5 text-[10px] font-medium transition-colors',
                  active ? 'text-accent' : 'text-ink-3',
                )}
              >
                {active && <span className="absolute top-0 h-0.5 w-7 rounded-full bg-accent" />}
                <Icon className="h-5 w-5" />
                {itemMeta.label}
                {item.key === 'tasks' && activeTaskCount > 0 && (
                  <span className="absolute right-[calc(50%-20px)] top-1.5 min-w-4 rounded-full bg-accent px-1 text-center text-[9px] font-semibold leading-4 text-[#06101f] tabular-nums">
                    {activeTaskCount > 99 ? '99+' : activeTaskCount}
                  </span>
                )}
              </button>
            )
          })}
        </div>
      </nav>
    </div>
  )
}

export function ShellPlaceholder({ label }: { label: string }) {
  return (
    <div className="flex min-h-[40vh] items-center justify-center text-sm text-ink-3">
      <Wifi className="mr-2 h-4 w-4 animate-pulse" />
      {label}
    </div>
  )
}
