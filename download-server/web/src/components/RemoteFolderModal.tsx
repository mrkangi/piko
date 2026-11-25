import { useEffect, useState } from 'react'
import { ArrowUp, Check, ChevronRight, Folder, FolderTree, Loader2 } from 'lucide-react'
import { browseRemoteFolders } from '../api'
import type { RemoteFolderEntry } from '../types'
import { Badge, Button, EmptyState, Modal, cn } from './ui'

interface Crumb {
  id: string
  name: string
}

export interface PickedRemoteFolder {
  id: string
  name: string
  path: string
}

interface Props {
  open: boolean
  existingRootIds: Set<string>
  onClose: () => void
  onPickMany: (folders: PickedRemoteFolder[]) => void
}

export function RemoteFolderModal({ open, existingRootIds, onClose, onPickMany }: Props) {
  const [stack, setStack] = useState<Crumb[]>([{ id: '', name: 'PikPak 根目录' }])
  const [entries, setEntries] = useState<RemoteFolderEntry[]>([])
  const [nextCursor, setNextCursor] = useState('')
  const [selected, setSelected] = useState<Record<string, PickedRemoteFolder>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const current = stack[stack.length - 1]

  async function load(parentId: string, cursor = '', append = false) {
    setLoading(true)
    setError('')
    try {
      const result = await browseRemoteFolders(parentId, cursor)
      const dirs = (result.entries || []).filter((entry) => entry.is_dir)
      setEntries((prev) => (append ? [...prev, ...dirs] : dirs))
      setNextCursor(result.next_cursor || '')
    } catch (err) {
      setError(err instanceof Error ? err.message : '目录加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open) {
      setStack([{ id: '', name: 'PikPak 根目录' }])
      setSelected({})
      void load('')
    }
  }, [open])

  function enterFolder(item: RemoteFolderEntry) {
    const nextStack = [...stack, { id: item.id, name: item.name }]
    setStack(nextStack)
    void load(item.id)
  }

  function jumpToCrumb(index: number) {
    const nextStack = stack.slice(0, index + 1)
    setStack(nextStack)
    void load(nextStack[nextStack.length - 1].id)
  }

  function currentPathString(suffixName?: string): string {
    const names = stack.slice(1).map((item) => item.name)
    if (suffixName) names.push(suffixName)
    return '/' + names.join('/')
  }

  function toggleSelect(item: RemoteFolderEntry) {
    setSelected((prev) => {
      const copy = { ...prev }
      if (copy[item.id]) {
        delete copy[item.id]
      } else {
        copy[item.id] = {
          id: item.id,
          name: item.name,
          path: currentPathString(item.name),
        }
      }
      return copy
    })
  }

  const selectedList = Object.values(selected)

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="选择 PikPak 监控目录"
      description="支持勾选多个目录批量加入监控，或直接选用当前目录。"
      icon={<FolderTree className="h-4 w-4" />}
      size="lg"
      footer={
        <>
          <span className="mr-auto text-[11px] text-ink-3">
            已勾选 <strong className="font-medium text-ink-2 tabular-nums">{selectedList.length}</strong> 个目录
          </span>
          <Button variant="secondary" onClick={onClose}>
            取消
          </Button>
          <Button
            variant="primary"
            disabled={selectedList.length === 0}
            onClick={() => {
              onPickMany(selectedList)
              onClose()
            }}
          >
            <Check className="h-3.5 w-3.5" />
            确认添加
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 items-center gap-1 overflow-x-auto text-xs text-ink-2 no-scrollbar">
            {stack.map((crumb, index) => (
              <div key={crumb.id || 'root'} className="flex shrink-0 items-center gap-1">
                {index > 0 && <ChevronRight className="h-3.5 w-3.5 text-ink-3" />}
                <button
                  type="button"
                  onClick={() => jumpToCrumb(index)}
                  className={cn(
                    'cursor-pointer rounded px-1.5 py-1 transition-colors hover:bg-surface-2 hover:text-ink',
                    index === stack.length - 1 && 'text-ink',
                  )}
                >
                  {crumb.name}
                </button>
              </div>
            ))}
          </div>
          <div className="grid shrink-0 grid-cols-2 gap-2">
            <Button variant="secondary" disabled={stack.length <= 1 || loading} onClick={() => jumpToCrumb(stack.length - 2)}>
              <ArrowUp className="h-3.5 w-3.5" />
              上级
            </Button>
            <Button
              variant="soft"
              onClick={() => {
                onPickMany([
                  {
                    id: current.id,
                    name: current.id === '' ? 'PikPakRoot' : current.name,
                    path: currentPathString(),
                  },
                ])
                onClose()
              }}
            >
              <Check className="h-3.5 w-3.5" />
              选用当前
            </Button>
          </div>
        </div>

        {error && <div className="rounded-lg border border-rose-500/30 bg-rose-500/8 px-3 py-2 text-xs text-rose-300">{error}</div>}

        {loading && entries.length === 0 ? (
          <div className="flex items-center justify-center py-12 text-xs text-ink-3">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" /> 正在读取远端目录…
          </div>
        ) : entries.length === 0 ? (
          <EmptyState icon={<Folder className="h-5 w-5" />} title="当前目录下没有子文件夹" description="可以直接选用当前目录加入监控。" />
        ) : (
          <ul className="space-y-1.5">
            {entries.map((item) => {
              const checked = Boolean(selected[item.id])
              const alreadyAdded = existingRootIds.has(item.id)
              return (
                <li
                  key={item.id}
                  className={cn(
                    'flex items-center justify-between gap-2 rounded-lg border px-2.5 py-2 transition-colors',
                    checked ? 'border-accent/40 bg-accent/10' : 'border-line bg-canvas/40 hover:bg-surface-2/50',
                  )}
                >
                  <div className="flex min-w-0 flex-1 items-center gap-2.5">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleSelect(item)}
                      className="h-4 w-4 shrink-0 cursor-pointer accent-[var(--color-accent)]"
                    />
                    <button
                      type="button"
                      onClick={() => enterFolder(item)}
                      className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 text-left text-sm text-ink-2 hover:text-ink"
                    >
                      <Folder className="h-4 w-4 shrink-0 text-amber-400" />
                      <span className="truncate">{item.name}</span>
                      {alreadyAdded && <Badge tone="success">已在监控</Badge>}
                    </button>
                  </div>
                  <Button variant="ghost" size="sm" onClick={() => enterFolder(item)} className="shrink-0">
                    进入
                  </Button>
                </li>
              )
            })}
          </ul>
        )}

        {nextCursor && (
          <div className="text-center">
            <Button variant="secondary" size="sm" disabled={loading} onClick={() => void load(current.id, nextCursor, true)}>
              {loading ? '加载中…' : '加载更多目录'}
            </Button>
          </div>
        )}
      </div>
    </Modal>
  )
}
