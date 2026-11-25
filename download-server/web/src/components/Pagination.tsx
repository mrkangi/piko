import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'

interface Props {
  total: number
  limit: number
  offset: number
  onOffsetChange: (offset: number) => void
}

export function Pagination({ total, limit, offset, onOffsetChange }: Props) {
  const pageCount = Math.max(1, Math.ceil(total / limit))
  const currentPage = Math.min(pageCount, Math.floor(offset / limit) + 1)
  const firstOffset = 0
  const lastOffset = (pageCount - 1) * limit

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-slate-800/80 bg-slate-900/60 px-3 py-2.5 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:px-4">
      <span className="text-center text-xs text-slate-400 sm:text-left">
        第 <strong className="text-slate-200 tabular-nums">{currentPage}</strong> / {pageCount} 页
        <span className="mx-2 text-slate-600">·</span>
        共 <strong className="text-slate-200 tabular-nums">{total}</strong> 条
        <span className="hidden sm:inline">
          <span className="mx-2 text-slate-600">·</span>
          本页 {Math.max(0, Math.min(limit, total - offset))} 条
        </span>
      </span>
      <div className="grid grid-cols-4 items-center gap-1.5 sm:flex">
        <button
          onClick={() => onOffsetChange(firstOffset)}
          disabled={offset <= 0}
          title="第一页"
          className="inline-flex items-center justify-center rounded-lg border border-slate-700 bg-slate-800 p-1.5 text-slate-300 hover:bg-slate-700 disabled:opacity-35 cursor-pointer"
        >
          <ChevronsLeft className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={() => onOffsetChange(Math.max(0, offset - limit))}
          disabled={offset <= 0}
          className="inline-flex items-center justify-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 disabled:opacity-35 cursor-pointer"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
          上一页
        </button>
        <button
          onClick={() => onOffsetChange(offset + limit)}
          disabled={offset+limit >= total}
          className="inline-flex items-center justify-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-xs text-slate-200 hover:bg-slate-700 disabled:opacity-35 cursor-pointer"
        >
          下一页
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
        <button
          onClick={() => onOffsetChange(lastOffset)}
          disabled={offset+limit >= total}
          title="最后一页"
          className="inline-flex items-center justify-center rounded-lg border border-slate-700 bg-slate-800 p-1.5 text-slate-300 hover:bg-slate-700 disabled:opacity-35 cursor-pointer"
        >
          <ChevronsRight className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  )
}
