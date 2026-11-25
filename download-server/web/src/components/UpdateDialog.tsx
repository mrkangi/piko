import { ArrowUpCircle, ExternalLink, Loader2, RefreshCw, ShieldCheck } from 'lucide-react'
import type { UpdateInfo } from '../types'
import { formatBytes } from '../format'
import { Badge, Button, Modal } from './ui'

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
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function UpdateDialog({ info, checking, applying, restarting, error, onClose, onApply, onRecheck }: UpdateDialogProps) {
  const busy = applying || restarting
  const channelLabel = info?.channel === 'test' ? '测试版通道' : info?.channel === 'dev' ? '开发构建' : '稳定版通道'

  return (
    <Modal
      open
      onClose={onClose}
      busy={busy}
      title="在线升级"
      description={
        <>
          当前版本 <span className="font-mono text-ink-2">{info?.current_version || '…'}</span> · {channelLabel}
        </>
      }
      icon={<ArrowUpCircle className="h-4 w-4" />}
      footer={
        <>
          <Button variant="secondary" onClick={onRecheck} disabled={checking || busy} loading={checking}>
            <RefreshCw className="h-3.5 w-3.5" />
            重新检查
          </Button>
          {info?.available && info.can_apply ? (
            <Button variant="primary" onClick={onApply} disabled={!info.can_apply || busy} loading={busy}>
              <ArrowUpCircle className="h-3.5 w-3.5" />
              {restarting ? '重启中' : applying ? '升级中' : '立即升级'}
            </Button>
          ) : (
            <Button variant="secondary" onClick={onClose}>
              关闭
            </Button>
          )}
        </>
      }
    >
      <div className="space-y-3.5">
        {restarting && (
          <div className="flex items-center gap-2.5 rounded-lg border border-emerald-500/25 bg-emerald-500/8 px-3 py-3 text-xs text-emerald-200">
            <Loader2 className="h-4 w-4 animate-spin" />
            升级包已就绪，服务正在重启，页面会自动刷新…
          </div>
        )}
        {applying && !restarting && (
          <div className="flex items-center gap-2.5 rounded-lg border border-accent/25 bg-accent/8 px-3 py-3 text-xs text-accent">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在下载并校验升级包，请保持页面打开…
          </div>
        )}

        {error && <div className="rounded-lg border border-rose-500/30 bg-rose-500/8 px-3 py-2.5 text-xs text-rose-200">{error}</div>}
        {info?.check_error && (
          <div className="rounded-lg border border-amber-500/25 bg-amber-500/8 px-3 py-2.5 text-xs text-amber-200">{info.check_error}</div>
        )}

        {!info ? (
          <div className="flex items-center gap-2 py-6 text-xs text-ink-3">
            <Loader2 className="h-4 w-4 animate-spin" />
            {checking ? '正在检查新版本…' : '正在获取版本信息…'}
          </div>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-2">
              <InfoTile label="最新版本" value={info.latest_version || '—'} mono />
              <InfoTile label="发布时间" value={formatTime(info.published_at) || '—'} />
            </div>

            {info.available && info.can_apply && (
              <div className="flex items-start gap-2 rounded-lg border border-emerald-500/25 bg-emerald-500/8 px-3 py-2.5 text-[11px] leading-5 text-emerald-200">
                <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" />
                <span>
                  升级包会先校验 SHA256，再替换当前程序并自动重启；进行中的下载任务会在重启后恢复。
                  {info.asset_size ? ` 下载大小约 ${formatBytes(info.asset_size)}。` : ''}
                </span>
              </div>
            )}

            {!info.can_apply && info.reason && (
              <div className="rounded-lg border border-amber-500/25 bg-amber-500/8 px-3 py-2.5 text-[11px] text-amber-200">{info.reason}</div>
            )}
            {!info.available && !info.check_error && info.reason && (
              <div className="rounded-lg border border-line bg-canvas/40 px-3 py-2.5 text-[11px] text-ink-2">{info.reason}</div>
            )}

            {info.available && <Badge tone="active">发现新版本</Badge>}

            {info.notes && (
              <div>
                <div className="mb-1.5 flex items-center justify-between text-xs text-ink-3">
                  <span>更新说明</span>
                  {info.release_url && (
                    <a
                      href={info.release_url}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 text-accent hover:underline"
                    >
                      查看发布页
                      <ExternalLink className="h-3 w-3" />
                    </a>
                  )}
                </div>
                <pre className="max-h-52 overflow-auto whitespace-pre-wrap rounded-lg border border-line bg-canvas/50 px-3 py-2.5 text-[11px] leading-5 text-ink-2">
                  {info.notes}
                </pre>
              </div>
            )}
          </>
        )}
      </div>
    </Modal>
  )
}

function InfoTile({ label, value, mono = false }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-line bg-canvas/40 px-3 py-2.5">
      <div className="text-[10px] font-medium uppercase tracking-wide text-ink-3">{label}</div>
      <div className={`mt-1 break-words text-[11px] leading-4 text-ink-2 ${mono ? 'font-mono' : ''}`}>{value}</div>
    </div>
  )
}
