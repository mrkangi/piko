import { ArrowUpCircle, ExternalLink, Loader2, RefreshCw, ShieldCheck, X } from 'lucide-react'
import type { UpdateInfo } from '../types'
import { formatBytes } from '../format'

interface UpdateDialogProps {
  info: UpdateInfo | null
  checking: boolean
  applying: boolean
  restarting: boolean
  error: string
  onClose: () => void
  onApply: () => void
  onRecheck: () => void
}

function formatTime(raw?: string): string {
  if (!raw) return ''
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return raw
  return date.toLocaleString()
}

export function UpdateDialog({ info, checking, applying, restarting, error, onClose, onApply, onRecheck }: UpdateDialogProps) {
  const busy = applying || restarting
  const channelLabel = info?.channel === 'test' ? '测试版通道' : info?.channel === 'dev' ? '开发构建' : '稳定版通道'

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4 backdrop-blur-sm"
      onMouseDown={() => {
        if (!busy) onClose()
      }}
    >
      <div
        className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-2xl border border-slate-700 bg-slate-900 shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4 border-b border-slate-800 px-5 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl border border-blue-500/30 bg-blue-500/15 text-blue-300">
              <ArrowUpCircle className="h-5 w-5" />
            </div>
            <div>
              <h2 className="text-base font-semibold">在线升级</h2>
              <p className="text-xs text-slate-400">
                当前版本 <span className="font-mono text-slate-300">{info?.current_version || '…'}</span> · {channelLabel}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            disabled={busy}
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-800 hover:text-slate-200 disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer"
            aria-label="关闭"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          {restarting ? (
            <div className="flex items-center gap-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-4 py-5 text-sm text-emerald-200">
              <Loader2 className="h-5 w-5 animate-spin" />
              升级包已就绪，服务正在重启，页面会自动刷新…
            </div>
          ) : applying ? (
            <div className="flex items-center gap-3 rounded-xl border border-blue-500/30 bg-blue-500/10 px-4 py-5 text-sm text-blue-200">
              <Loader2 className="h-5 w-5 animate-spin" />
              正在下载并校验升级包，请保持页面打开…
            </div>
          ) : null}

          {error && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2.5 text-xs text-rose-200">{error}</div>
          )}
          {info?.check_error && (
            <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2.5 text-xs text-amber-200">
              {info.check_error}
            </div>
          )}

          {!info ? (
            <div className="flex items-center gap-2 py-6 text-sm text-slate-400">
              <Loader2 className="h-4 w-4 animate-spin" />
              {checking ? '正在检查新版本…' : '正在获取版本信息…'}
            </div>
          ) : info ? (
            <>
              <div className="grid grid-cols-2 gap-3 text-xs">
                <div className="rounded-xl border border-slate-800 bg-slate-950/60 px-3 py-2.5">
                  <div className="text-slate-500">最新版本</div>
                  <div className="mt-1 truncate font-mono text-slate-200">{info.latest_version || '—'}</div>
                </div>
                <div className="rounded-xl border border-slate-800 bg-slate-950/60 px-3 py-2.5">
                  <div className="text-slate-500">发布时间</div>
                  <div className="mt-1 truncate text-slate-200">{formatTime(info.published_at) || '—'}</div>
                </div>
              </div>

              {info.available && info.can_apply && (
                <div className="flex items-start gap-2 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3 py-2.5 text-xs text-emerald-200">
                  <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>
                    升级包会先校验 SHA256，再替换当前程序并自动重启；进行中的下载任务会在重启后恢复。
                    {info.asset_size ? ` 下载大小约 ${formatBytes(info.asset_size)}。` : ''}
                  </span>
                </div>
              )}

              {!info.can_apply && info.reason && (
                <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2.5 text-xs text-amber-200">{info.reason}</div>
              )}
              {!info.available && !info.check_error && info.reason && (
                <div className="rounded-xl border border-slate-700 bg-slate-950/60 px-3 py-2.5 text-xs text-slate-300">{info.reason}</div>
              )}

              {info.notes && (
                <div>
                  <div className="mb-1.5 flex items-center justify-between text-xs text-slate-400">
                    <span>更新说明</span>
                    {info.release_url && (
                      <a
                        href={info.release_url}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 text-blue-300 hover:text-blue-200"
                      >
                        查看发布页
                        <ExternalLink className="h-3 w-3" />
                      </a>
                    )}
                  </div>
                  <pre className="max-h-52 overflow-auto whitespace-pre-wrap rounded-xl border border-slate-800 bg-slate-950/70 px-3 py-2.5 text-xs leading-relaxed text-slate-300">
                    {info.notes}
                  </pre>
                </div>
              )}
            </>
          ) : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-800 bg-slate-950/40 px-5 py-3">
          <button
            onClick={onRecheck}
            disabled={checking || busy}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-700 bg-slate-900 px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50 cursor-pointer"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${checking ? 'animate-spin' : ''}`} />
            重新检查
          </button>
          {info?.available ? (
            <button
              onClick={onApply}
              disabled={!info.can_apply || busy}
              className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50 cursor-pointer"
            >
              {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ArrowUpCircle className="h-3.5 w-3.5" />}
              {restarting ? '重启中' : applying ? '升级中' : '立即升级'}
            </button>
          ) : (
            <button
              onClick={onClose}
              className="rounded-lg border border-slate-700 bg-slate-900 px-3 py-1.5 text-xs text-slate-300 hover:bg-slate-800 cursor-pointer"
            >
              关闭
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
