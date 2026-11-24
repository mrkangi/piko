import { Search, ShieldCheck, AlertCircle } from 'lucide-react'
import type { FileView, Source } from '../types'
import { formatBytes, formatTimestamp, TASK_STATE_META } from '../format'

interface Props {
  files: FileView[]
  sources: Source[]
  remoteStateFilter: string
  sourceFilter: string
  searchQuery: string
  onRemoteStateChange: (state: string) => void
  onSourceFilterChange: (sourceId: string) => void
  onSearchChange: (q: string) => void
}

export function FilesTab({
  files,
  sources,
  remoteStateFilter,
  sourceFilter,
  searchQuery,
  onRemoteStateChange,
  onSourceFilterChange,
  onSearchChange,
}: Props) {
  const sourceMap = new Map(sources.map((s) => [s.id, s.local_alias]))

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex gap-1.5">
          {[
            { key: '', label: '全部文件' },
            { key: 'PRESENT', label: '远端存在' },
            { key: 'OUT_OF_SCOPE', label: '不在监控范围（保留本地）' },
          ].map((item) => (
            <button
              key={item.key || 'all'}
              onClick={() => onRemoteStateChange(item.key)}
              className={`rounded-lg px-3 py-1.5 text-xs font-medium transition cursor-pointer ${
                remoteStateFilter === item.key
                  ? 'bg-blue-600 text-white'
                  : 'border border-slate-800 bg-slate-900 text-slate-400 hover:text-slate-200'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative flex-1 sm:w-56 sm:flex-none">
            <Search className="absolute left-3 top-2.5 h-3.5 w-3.5 text-slate-500" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => onSearchChange(e.target.value)}
              placeholder="搜索远端或本地路径…"
              className="w-full rounded-lg border border-slate-800 bg-slate-900 pl-8 pr-3 py-1.5 text-xs text-slate-100 placeholder:text-slate-500 focus:border-blue-500 focus:outline-none"
            />
          </div>
          <select
            value={sourceFilter}
            onChange={(e) => onSourceFilterChange(e.target.value)}
            className="rounded-lg border border-slate-800 bg-slate-900 px-3 py-1.5 text-xs text-slate-200 focus:border-blue-500 focus:outline-none"
          >
            <option value="">全部来源</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>{s.local_alias}</option>
            ))}
          </select>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border border-slate-800 bg-slate-900/70">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-800 bg-slate-950/60 text-xs uppercase text-slate-400">
              <tr>
                <th className="px-4 py-3 font-medium">远端相对路径</th>
                <th className="px-4 py-3 font-medium">大小</th>
                <th className="px-4 py-3 font-medium">远端状态</th>
                <th className="px-4 py-3 font-medium">本地交付状态</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/70">
              {files.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-4 py-12 text-center text-sm text-slate-500">
                    暂无文件索引记录
                  </td>
                </tr>
              ) : (
                files.map((item) => {
                  const obj = item.remote_object
                  const outOfScope = obj.remote_state === 'OUT_OF_SCOPE'
                  return (
                    <tr key={obj.id} className="hover:bg-slate-800/35">
                      <td className="px-4 py-3 max-w-md">
                        <div className="truncate font-medium text-slate-100" title={obj.relative_path}>
                          {obj.relative_path}
                        </div>
                        <div className="mt-0.5 flex items-center gap-2 text-xs text-slate-400">
                          <span className="rounded bg-slate-800 px-1.5 py-0.5 text-[11px] text-slate-300">
                            {sourceMap.get(obj.source_id) || obj.source_id}
                          </span>
                          <span>最近发现：{formatTimestamp(obj.last_seen_at)}</span>
                        </div>
                      </td>

                      <td className="px-4 py-3 whitespace-nowrap text-xs text-slate-300 tabular-nums">
                        {formatBytes(obj.size)}
                      </td>

                      <td className="px-4 py-3 whitespace-nowrap">
                        {outOfScope ? (
                          <span className="inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/15 px-2.5 py-0.5 text-xs text-amber-300">
                            <AlertCircle className="h-3.5 w-3.5" />
                            不在监控范围（保留本地）
                          </span>
                        ) : (
                          <span className="inline-flex items-center rounded-full border border-emerald-500/30 bg-emerald-500/15 px-2.5 py-0.5 text-xs text-emerald-300">
                            远端存在
                          </span>
                        )}
                      </td>

                      <td className="px-4 py-3">
                        {item.delivery ? (
                          <div className="space-y-1">
                            <div className="flex items-center gap-1.5 text-xs text-slate-200">
                              <ShieldCheck className="h-3.5 w-3.5 text-emerald-400 shrink-0" />
                              <span className="truncate max-w-md" title={item.delivery.local_rel_path}>
                                {item.delivery.local_rel_path}
                              </span>
                              {item.path_moved && (
                                <span className="rounded border border-amber-500/30 bg-amber-500/15 px-1.5 py-0.5 text-[11px] text-amber-300">
                                  远端路径已变更
                                </span>
                              )}
                            </div>
                            <div className="text-[11px] font-mono text-slate-500">
                              SHA256: {item.delivery.local_sha256.slice(0, 16)}…
                            </div>
                          </div>
                        ) : item.latest_task ? (
                          <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-xs ${TASK_STATE_META[item.latest_task.state].badgeClass}`}>
                            {TASK_STATE_META[item.latest_task.state].label}
                          </span>
                        ) : (
                          <span className="text-xs text-slate-500">基线存量（未入队）</span>
                        )}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
