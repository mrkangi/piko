import { useEffect, useState } from 'react'
import { ArrowUp, Check, Folder, FolderOpen, Loader2 } from 'lucide-react'
import { browseLocalDirs } from '../api'
import type { LocalDirEntry } from '../types'
import { Button, EmptyState, Modal } from './ui'

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
      const result = await browseLocalDirs(target)
      setCurrentPath(result.current_path)
      setParentPath(result.parent_path || '')
      setDirs(result.dirs || [])
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

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="选择服务端下载目录"
      description="选择服务器上用于存放下载文件与暂存的根目录。"
      icon={<FolderOpen className="h-4 w-4" />}
      size="lg"
      footer={
        <>
          <Button variant="secondary" disabled={!parentPath || loading} onClick={() => void load(parentPath)}>
            <ArrowUp className="h-3.5 w-3.5" />
            上级目录
          </Button>
          <Button variant="primary" disabled={!currentPath} onClick={() => { onPick(currentPath); onClose() }}>
            <Check className="h-3.5 w-3.5" />
            选用当前目录
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <div className="flex items-center gap-2 rounded-lg border border-line bg-canvas/50 px-2.5 py-2">
          <Folder className="h-4 w-4 shrink-0 text-cyan-400" />
          <div className="min-w-0 flex-1 truncate font-mono text-[11px] text-ink-2" title={currentPath}>
            {currentPath || '/'}
          </div>
        </div>

        {error && <div className="rounded-lg border border-rose-500/30 bg-rose-500/8 px-3 py-2 text-xs text-rose-300">{error}</div>}

        {loading ? (
          <div className="flex items-center justify-center py-12 text-xs text-ink-3">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" /> 正在读取目录…
          </div>
        ) : dirs.length === 0 ? (
          <EmptyState icon={<Folder className="h-5 w-5" />} title="当前目录下没有子文件夹" description="可以直接选用当前目录作为下载根目录。" />
        ) : (
          <ul className="space-y-1.5">
            {dirs.map((dir) => (
              <li key={dir.path} className="flex items-center gap-2 rounded-lg border border-line bg-canvas/40 px-2.5 py-2 transition-colors hover:bg-surface-2/60">
                <button
                  type="button"
                  onClick={() => void load(dir.path)}
                  className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 text-left text-sm text-ink-2 hover:text-ink"
                >
                  <Folder className="h-4 w-4 shrink-0 text-cyan-400" />
                  <span className="truncate">{dir.name}</span>
                </button>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Button variant="ghost" size="sm" onClick={() => void load(dir.path)}>
                    进入
                  </Button>
                  <Button variant="soft" size="sm" onClick={() => { onPick(dir.path); onClose() }}>
                    选用
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  )
}
