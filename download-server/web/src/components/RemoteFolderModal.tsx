import { useEffect, useState } from 'react'
import { ChevronRight, Folder, ArrowUp, X, Loader2, Check } from 'lucide-react'
import { browseRemoteFolders } from '../api'
import type { RemoteFolderEntry } from '../types'

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
      const res = await browseRemoteFolders(parentId, cursor)
      const dirs = (res.entries || []).filter((e) => e.is_dir)
      setEntries((prev) => (append ? [...prev, ...dirs] : dirs))
      setNextCursor(res.next_cursor || '')
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
      load('')
    }
  }, [open])

  if (!open) return null

  function enterFolder(item: RemoteFolderEntry) {
    const nextStack = [...stack, { id: item.id, name: item.name }]
    setStack(nextStack)
    load(item.id)
  }

  function jumpToCrumb(index: number) {
    const nextStack = stack.slice(0, index + 1)
    setStack(nextStack)
    load(nextStack[nextStack.length - 1].id)
  }

  function currentPathString(suffixName?: string): string {
    const names = stack.slice(1).map((x) => x.name)
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-xs">
      <div className="flex max-h-[82vh] w-full max-w-2xl flex-col rounded-2xl border border-slate-800 bg-slate-900 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-4 py-4 sm:px-5">
          <div>
            <h3 className="text-base font-semibold text-slate-100">选择 PikPak 监控目录</h3>
            <p className="text-xs text-slate-400">支持勾选多个目录批量加入监控，或直接选用当前目录</p>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200 cursor-pointer">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex flex-col gap-2 border-b border-slate-800/80 bg-slate-950/50 px-4 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:px-5">
          <div className="flex w-full items-center gap-1 overflow-x-auto text-xs text-slate-300">
            {stack.map((crumb, idx) => (
              <div key={crumb.id || 'root'} className="flex items-center gap-1 shrink-0">
                {idx > 0 && <ChevronRight className="h-3.5 w-3.5 text-slate-600" />}
                <button
                  onClick={() => jumpToCrumb(idx)}
                  className="rounded px-1.5 py-0.5 hover:bg-slate-800 hover:text-white cursor-pointer"
                >
                  {crumb.name}
                </button>
              </div>
            ))}
          </div>
          <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:shrink-0 sm:items-center">
            <button
              disabled={stack.length <= 1 || loading}
              onClick={() => jumpToCrumb(stack.length - 2)}
              className="inline-flex items-center justify-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
            >
              <ArrowUp className="h-3.5 w-3.5" />
              上级
            </button>
            <button
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
              className="inline-flex items-center justify-center gap-1 rounded-lg border border-blue-500/40 bg-blue-600/20 px-3 py-1 text-xs font-medium text-blue-300 hover:bg-blue-600/30 cursor-pointer"
            >
              <Check className="h-3.5 w-3.5" />
              选用当前目录
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-3">
          {error && (
            <div className="mb-3 rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
              {error}
            </div>
          )}
          {loading && entries.length === 0 ? (
            <div className="flex items-center justify-center py-12 text-sm text-slate-400">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" /> 正在读取远端目录…
            </div>
          ) : entries.length === 0 ? (
            <div className="py-12 text-center text-sm text-slate-500">当前目录下没有子文件夹</div>
          ) : (
            <ul className="space-y-1.5">
              {entries.map((item) => {
                const checked = Boolean(selected[item.id])
                const alreadyAdded = existingRootIds.has(item.id)
                return (
                <li
                  key={item.id}
                  className={`flex items-center justify-between rounded-xl border px-3.5 py-2.5 transition ${
                    checked
                      ? 'border-blue-500/50 bg-blue-500/10'
                      : 'border-slate-800/80 bg-slate-950/60 hover:border-slate-700'
                  }`}
                >
                  <div className="flex flex-1 items-center gap-3 min-w-0">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggleSelect(item)}
                      className="h-4 w-4 rounded border-slate-600 bg-slate-900 text-blue-600 cursor-pointer"
                    />
                    <button
                      onClick={() => enterFolder(item)}
                      className="flex flex-1 items-center gap-2.5 text-left text-sm text-slate-200 hover:text-white min-w-0 cursor-pointer"
                    >
                      <Folder className="h-4 w-4 shrink-0 text-amber-400" />
                      <span className="truncate">{item.name}</span>
                      {alreadyAdded && (
                        <span className="rounded bg-emerald-500/15 border border-emerald-500/30 px-1.5 py-0.5 text-[10px] text-emerald-300 shrink-0">
                          已在监控
                        </span>
                      )}
                    </button>
                  </div>
                  <div className="ml-2 flex items-center gap-1.5 sm:ml-3 sm:gap-2">
                    <button
                      onClick={() => enterFolder(item)}
                      className="rounded-lg border border-slate-700 px-2.5 py-1 text-xs text-slate-300 hover:bg-slate-800 cursor-pointer"
                    >
                      进入
                    </button>
                  </div>
                </li>
                )
              })}
            </ul>
          )}

          {nextCursor && (
            <div className="mt-3 text-center">
              <button
                disabled={loading}
                onClick={() => load(current.id, nextCursor, true)}
                className="rounded-lg border border-slate-700 bg-slate-800 px-4 py-1.5 text-xs text-slate-200 hover:bg-slate-700 cursor-pointer"
              >
                {loading ? '加载中…' : '加载更多目录'}
              </button>
            </div>
          )}
        </div>

        <div className="flex flex-col gap-2 border-t border-slate-800 bg-slate-950/40 px-4 py-3.5 sm:flex-row sm:items-center sm:justify-between sm:px-5">
          <span className="text-xs text-slate-400">
            已勾选 <strong className="text-slate-200 tabular-nums">{selectedList.length}</strong> 个目录
          </span>
          <div className="grid w-full grid-cols-2 gap-2 sm:flex sm:w-auto sm:items-center">
            <button onClick={onClose} className="rounded-lg border border-slate-700 px-3 py-1.5 text-center text-xs text-slate-300 hover:bg-slate-800 cursor-pointer">
              取消
            </button>
            <button
              disabled={selectedList.length === 0}
              onClick={() => {
                onPickMany(selectedList)
                onClose()
              }}
              className="rounded-lg bg-blue-600 px-4 py-1.5 text-center text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-40 cursor-pointer"
            >
              确认添加所选目录
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
