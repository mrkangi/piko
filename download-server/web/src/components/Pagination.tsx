import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react'
import { Button } from './ui'

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
  const atStart = offset <= 0
  const atEnd = offset + limit >= total

  return (
    <div className="flex items-center justify-between gap-3 rounded-lg border border-line bg-surface-1 px-3 py-2">
      <span className="min-w-0 whitespace-nowrap text-[10px] text-ink-3 sm:text-[11px]">
        第 <strong className="font-medium text-ink-2 tabular-nums">{currentPage}</strong> / {pageCount} 页
        <span className="hidden sm:inline">
          <span className="mx-1.5 text-line-strong">·</span>
          共 <strong className="font-medium text-ink-2 tabular-nums">{total}</strong> 条
          <span className="mx-1.5 text-line-strong">·</span>本页 {Math.max(0, Math.min(limit, total - offset))} 条
        </span>
      </span>
      <div className="grid shrink-0 grid-cols-4 items-center gap-1.5">
        <Button variant="secondary" size="icon-sm" onClick={() => onOffsetChange(firstOffset)} disabled={atStart} title="第一页" aria-label="第一页">
          <ChevronsLeft className="h-3.5 w-3.5" />
        </Button>
        <Button variant="secondary" size="sm" onClick={() => onOffsetChange(Math.max(0, offset - limit))} disabled={atStart} aria-label="上一页">
          <ChevronLeft className="h-3.5 w-3.5" />
          <span className="hidden sm:inline">上一页</span>
        </Button>
        <Button variant="secondary" size="sm" onClick={() => onOffsetChange(offset + limit)} disabled={atEnd} aria-label="下一页">
          <span className="hidden sm:inline">下一页</span>
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
        <Button variant="secondary" size="icon-sm" onClick={() => onOffsetChange(lastOffset)} disabled={atEnd} title="最后一页" aria-label="最后一页">
          <ChevronsRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  )
}
