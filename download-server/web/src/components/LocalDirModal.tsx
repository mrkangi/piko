import { useEffect, useState } from 'react'
import { ArrowUp, Check, Folder, Loader2, X } from 'lucide-react'
import { browseLocalDirs } from '../api'
import type { LocalDirEntry } from '../types'

interface Props {
  open: boolean
  initialPath: string
  onClose: () => void
  onPick: (dirPath: string) => void
}

export function LocalDirModal({ open, initialPath, onClose, onPick }: Props) {
  const [currentPath, setCurrentPath] = useState('')
  const [parentPath, setParentPath] = useState('')
  const [dirs, setDirs] = useState<LocalDirEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function load(target: string) {
    setLoading(true)
    setError('')
    try {
      const res = await browseLocalDirs(target)
      setCurrentPath(res.current_path)
      setParentPath(res.parent_path || '')
      setDirs(res.dirs || [])
    } catch (err) {
      setError(err instanceof Error ? err.message : '读取服务器目录失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (open) {
      void load(initialPath)
    }
  }, [open, initialPath])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-xs">
      <div className="flex max-h-[82vh] w-full max-w-2xl flex-col rounded-2xl border border-slate-800 bg-slate-900 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-5 py-4">
          <div>
            <h3 className="text-base font-semibold text-slate-100">选择服务端下载目录</h3>
            <p className="text-xs text-slate-400">选择服务器上用于存放下载文件与暂存的根目录</p>
          </div>
          <button onClick={onClose} className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-slate-200 cursor-pointer">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="flex items-center justify-between gap-2 border-b border-slate-800/80 bg-slate-950/50 px-5 py-2.5">
          <div className="truncate font-mono text-xs text-slate-300" title={currentPath}>
            {currentPath || '/'}
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button
              disabled={!parentPath || loading}
              onClick={() => void load(parentPath)}
              className="inline-flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1 text-xs text-slate-200 hover:bg-slate-700 disabled:opacity-40 cursor-pointer"
            >
              <ArrowUp className="h-3.5 w-3.5" />
              上级
            </button>
            <button
              disabled={!currentPath}
              onClick={() => {
                onPick(currentPath)
                onClose()
              }}
              className="inline-flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1 text-xs font-medium text-white hover:bg-blue-500 disabled:opacity-40 cursor-pointer"
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
          {loading ? (
            <div className="flex items-center justify-center py-12 text-sm text-slate-400">
              <Loader2 className="mr-2 h-4 w-4 animate-spin" /> 正在读取目录…
            </div>
          ) : dirs.length === 0 ? (
            <div className="py-12 text-center text-sm text-slate-500">当前目录下没有子文件夹</div>
          ) : (
            <ul className="space-y-1.5">
              {dirs.map((dir) => (
                <li
                  key={dir.path}
                  className="flex items-center justify-between rounded-xl border border-slate-800/80 bg-slate-950/60 px-3.5 py-2.5 hover:border-slate-700"
                >
                  <button
                    onClick={() => void load(dir.path)}
                    className="flex flex-1 items-center gap-2.5 text-left text-sm text-slate-200 hover:text-white cursor-pointer"
                  >
                    <Folder className="h-4 w-4 shrink-0 text-cyan-400" />
                    <span className="truncate">{dir.name}</span>
                  </button>
                  <div className="flex items-center gap-2 ml-3">
                    <button
                      onClick={() => void load(dir.path)}
                      className="rounded-lg border border-slate-700 px-2.5 py-1 text-xs text-slate-300 hover:bg-slate-800 cursor-pointer"
                    >
                      进入
                    </button>
                    <button
                      onClick={() => {
                        onPick(dir.path)
                        onClose()
                      }}
                      className="rounded-lg bg-blue-600/20 border border-blue-500/40 px-2.5 py-1 text-xs text-blue-300 hover:bg-blue-600/30 cursor-pointer"
                    >
                      选用
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  )
}
