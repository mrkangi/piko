import { FolderTree, Search, ShieldCheck } from 'lucide-react'
import type { FileView, Source } from '../types'
import { formatBytes, formatTimestamp, TASK_STATE_META } from '../format'
import { maskPath } from '../privacy'
import { Badge, Button, EmptyState, Segmented, StatusDot, cn, inputClassName } from './ui'
import { Pagination } from './Pagination'

interface Props {
  files: FileView[]
  sources: Source[]
  remoteStateFilter: string
  sourceFilter: string
  searchQuery: string
  hideFilenames: boolean
  total: number
  limit: number
  offset: number
  onRemoteStateChange: (state: string) => void
  onSourceFilterChange: (sourceId: string) => void
  onSearchChange: (q: string) => void
  onOffsetChange: (offset: number) => void
}

const REMOTE_STATE_OPTIONS = [
  { key: '', label: '全部文件' },
  { key: 'PRESENT', label: '远端存在' },
  { key: 'OUT_OF_SCOPE', label: '不在监控范围' },
]

function DeliveryCell({ item, hideFilenames }: { item: FileView; hideFilenames: boolean }) {
  if (item.delivery) {
    return (
      <div className="space-y-1">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <ShieldCheck className="h-3.5 w-3.5 shrink-0 text-emerald-400" />
          <span
            className="min-w-0 max-w-full truncate text-xs text-ink-2"
            title={hideFilenames ? undefined : item.delivery.local_rel_path}
          >
            {maskPath(item.delivery.local_rel_path, hideFilenames)}
          </span>
          {item.delivery.preexisting && <Badge tone="info">本地已存在</Badge>}
          {item.path_moved && <Badge tone="wait">远端路径已变更</Badge>}
        </div>
        {item.delivery.local_sha256 && (
          <div className="font-mono text-[10px] text-ink-3">SHA256 {item.delivery.local_sha256.slice(0, 16)}…</div>
        )}
      </div>
    )
  }
  if (item.latest_task) {
    const meta = TASK_STATE_META[item.latest_task.state]
    return <Badge tone={meta.tone}>{meta.label}</Badge>
  }
  return <span className="text-[11px] text-ink-3">基线存量（未入队）</span>
}

function RemoteStateBadge({ item }: { item: FileView }) {
  if (item.remote_object.remote_state === 'OUT_OF_SCOPE') {
    return <Badge tone="wait">不在监控范围</Badge>
  }
  return <Badge tone="success">远端存在</Badge>
}

export function FilesTab({
  files,
  sources,
  remoteStateFilter,
  sourceFilter,
  searchQuery,
  hideFilenames,
  total,
  limit,
  offset,
  onRemoteStateChange,
  onSourceFilterChange,
  onSearchChange,
  onOffsetChange,
}: Props) {
  const sourceMap = new Map(sources.map((source) => [source.id, source.local_alias]))
  const hasFilters = Boolean(remoteStateFilter || sourceFilter || searchQuery.trim())

  function clearFilters() {
    onRemoteStateChange('')
    onSourceFilterChange('')
    onSearchChange('')
    onOffsetChange(0)
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2.5 lg:flex-row lg:items-center lg:justify-between">
        <Segmented value={remoteStateFilter} options={REMOTE_STATE_OPTIONS} onChange={onRemoteStateChange} />
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
          <div className="relative min-w-0 flex-1 lg:w-64 lg:flex-none">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-3" />
            <input
              type="search"
              value={searchQuery}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder="搜索远端或本地路径…"
              className={cn(inputClassName, 'h-9 py-0 pl-8 text-xs')}
            />
          </div>
          <select
            value={sourceFilter}
            onChange={(event) => onSourceFilterChange(event.target.value)}
            className={cn(inputClassName, 'h-9 py-0 text-xs sm:w-auto')}
          >
            <option value="">全部来源</option>
            {sources.map((source) => (
              <option key={source.id} value={source.id}>{source.local_alias}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex items-center justify-between gap-2 rounded-lg border border-line bg-surface-1 px-3 py-2 text-[11px] text-ink-3">
        <span>共 <strong className="font-medium text-ink-2 tabular-nums">{total}</strong> 个文件索引</span>
        {hasFilters && (
          <Button variant="ghost" size="sm" onClick={clearFilters}>
            清除筛选
          </Button>
        )}
      </div>

      <div className="hidden overflow-hidden rounded-xl border border-line bg-surface-1 md:block">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-left text-[13px]">
            <thead className="border-b border-line bg-canvas/40 text-[11px] font-medium text-ink-3">
              <tr>
                <th className="px-4 py-2.5 font-medium">文件</th>
                <th className="w-24 px-4 py-2.5 font-medium">大小</th>
                <th className="w-32 px-4 py-2.5 font-medium">远端状态</th>
                <th className="w-[38%] px-4 py-2.5 font-medium">本地交付</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {files.length === 0 ? (
                <tr>
                  <td colSpan={4}>
                    <EmptyState
                      icon={<FolderTree className="h-5 w-5" />}
                      title="暂无文件索引记录"
                      description="来源扫描成功后，远端文件会出现在这里。"
                      action={hasFilters ? <Button variant="soft" size="sm" onClick={clearFilters}>清除筛选</Button> : undefined}
                    />
                  </td>
                </tr>
              ) : (
                files.map((item) => {
                  const object = item.remote_object
                  return (
                    <tr key={object.id} className="transition-colors hover:bg-surface-2/35">
                      <td className="max-w-md px-4 py-3">
                        <div className="truncate text-[13px] font-medium text-ink" title={hideFilenames ? undefined : object.relative_path}>
                          {maskPath(object.relative_path, hideFilenames)}
                        </div>
                        <div className="mt-1 flex items-center gap-1.5 text-[11px] text-ink-3">
                          <span className="rounded border border-line bg-surface-2 px-1.5 py-px text-[10px] text-ink-2">
                            {sourceMap.get(object.source_id) || object.source_id}
                          </span>
                          <span className="truncate">最近发现 {formatTimestamp(object.last_seen_at)}</span>
                        </div>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-xs text-ink-2 tabular-nums">{formatBytes(object.size)}</td>
                      <td className="px-4 py-3">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <StatusDot tone={object.remote_state === 'PRESENT' ? 'success' : 'wait'} />
                          <RemoteStateBadge item={item} />
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <DeliveryCell item={item} hideFilenames={hideFilenames} />
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="space-y-2.5 md:hidden">
        {files.length === 0 ? (
          <div className="rounded-xl border border-line bg-surface-1">
            <EmptyState
              icon={<FolderTree className="h-5 w-5" />}
              title="暂无文件索引记录"
              description="来源扫描成功后，远端文件会出现在这里。"
              action={hasFilters ? <Button variant="soft" size="sm" onClick={clearFilters}>清除筛选</Button> : undefined}
            />
          </div>
        ) : (
          files.map((item) => {
            const object = item.remote_object
            return (
              <article key={object.id} className="rounded-xl border border-line bg-surface-1 p-3">
                <div className="flex items-start gap-2.5">
                  <StatusDot tone={object.remote_state === 'PRESENT' ? 'success' : 'wait'} className="mt-1.5" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[13px] font-medium text-ink" title={hideFilenames ? undefined : object.relative_path}>
                      {maskPath(object.relative_path, hideFilenames)}
                    </div>
                    <div className="mt-1 flex min-w-0 items-center gap-1.5 text-[11px] text-ink-3">
                      <span className="rounded border border-line bg-surface-2 px-1.5 py-px text-[10px] text-ink-2">
                        {sourceMap.get(object.source_id) || object.source_id}
                      </span>
                      <span className="truncate">{formatTimestamp(object.last_seen_at)}</span>
                    </div>
                  </div>
                  <span className="shrink-0 text-[11px] text-ink-2 tabular-nums">{formatBytes(object.size)}</span>
                </div>

                <div className="mt-2 flex flex-wrap items-center gap-1.5">
                  <RemoteStateBadge item={item} />
                  {item.delivery?.preexisting && <Badge tone="info">本地已存在</Badge>}
                  {item.path_moved && <Badge tone="wait">远端路径已变更</Badge>}
                  {!item.delivery && item.latest_task && (
                    <Badge tone={TASK_STATE_META[item.latest_task.state].tone}>{TASK_STATE_META[item.latest_task.state].label}</Badge>
                  )}
                </div>

                <div className="mt-2.5 border-t border-line pt-2">
                  <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-ink-3">本地交付</div>
                  <DeliveryCell item={item} hideFilenames={hideFilenames} />
                </div>
              </article>
            )
          })
        )}
      </div>

      <Pagination total={total} limit={limit} offset={offset} onOffsetChange={onOffsetChange} />
    </div>
  )
}
