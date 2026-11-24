import { useState, type FormEvent } from 'react'
import { CloudDownload, KeyRound, User, Lock, FolderOpen } from 'lucide-react'
import { completeSetup } from '../api'
import type { AuthUser, SetupStatus } from '../types'

interface Props {
  status: SetupStatus
  onComplete: (user: AuthUser) => void
}

export function SetupView({ status, onComplete }: Props) {
  const [token, setToken] = useState('')
  const [adminUser, setAdminUser] = useState('admin')
  const [adminPass, setAdminPass] = useState('')
  const [adminPassConfirm, setAdminPassConfirm] = useState('')
  const [pikpakUser, setPikpakUser] = useState('')
  const [pikpakPass, setPikpakPass] = useState('')
  const [downloadDir, setDownloadDir] = useState(status.download_dir || '')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError('')
    if (adminPass !== adminPassConfirm) {
      setError('两次输入的管理员密码不一致')
      return
    }
    setLoading(true)
    try {
      const user = await completeSetup({
        token: token.trim(),
        admin_username: adminUser.trim(),
        admin_password: adminPass,
        pikpak_username: pikpakUser.trim(),
        pikpak_password: pikpakPass,
        download_dir: downloadDir.trim(),
      })
      onComplete(user)
    } catch (err) {
      setError(err instanceof Error ? err.message : '首次配置失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-slate-950 px-4 py-10">
      <div className="mx-auto w-full max-w-3xl">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-blue-500/30 bg-blue-500/15 text-blue-400">
            <CloudDownload className="h-6 w-6" />
          </div>
          <div>
            <h1 className="text-xl font-semibold text-slate-100">Piko Download Server 首次配置</h1>
            <p className="text-sm text-slate-400">配置管理员、PikPak 账号和本地下载目录。完成后即可进入管理面板。</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5 rounded-2xl border border-slate-800 bg-slate-900/80 p-6 shadow-2xl">
          <section className="space-y-3">
            <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-200">
              <KeyRound className="h-4 w-4 text-amber-400" /> 首次配置令牌
            </h2>
            <p className="text-xs text-slate-400">
              服务启动时已在控制台打印 Setup Token。默认监听 0.0.0.0，必须凭该令牌完成首次初始化。
            </p>
            <input
              type="password"
              required
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="粘贴控制台打印的 Setup Token"
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
          </section>

          <section className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <div className="space-y-3">
              <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-200">
                <User className="h-4 w-4 text-cyan-400" /> 管理员账号
              </h2>
              <input
                type="text"
                required
                value={adminUser}
                onChange={(e) => setAdminUser(e.target.value)}
                placeholder="管理员用户名"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
              <input
                type="password"
                required
                minLength={8}
                value={adminPass}
                onChange={(e) => setAdminPass(e.target.value)}
                placeholder="管理员密码（至少 8 位）"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
              <input
                type="password"
                required
                minLength={8}
                value={adminPassConfirm}
                onChange={(e) => setAdminPassConfirm(e.target.value)}
                placeholder="再次输入管理员密码"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
            </div>

            <div className="space-y-3">
              <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-200">
                <Lock className="h-4 w-4 text-emerald-400" /> PikPak 账号
              </h2>
              <input
                type="text"
                required
                value={pikpakUser}
                onChange={(e) => setPikpakUser(e.target.value)}
                placeholder="PikPak 用户名 / 邮箱"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
              <input
                type="password"
                required
                value={pikpakPass}
                onChange={(e) => setPikpakPass(e.target.value)}
                placeholder="PikPak 密码"
                className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
              />
              <div className="rounded-lg border border-slate-800 bg-slate-950/60 p-2.5 text-[11px] text-slate-400">
                密码会使用本地密钥加密后存入数据目录，不会写进日志。
              </div>
            </div>
          </section>

          <section className="space-y-3">
            <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-200">
              <FolderOpen className="h-4 w-4 text-amber-400" /> 本地下载目录
            </h2>
            <input
              type="text"
              required
              value={downloadDir}
              onChange={(e) => setDownloadDir(e.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-xs text-slate-100 focus:border-blue-500 focus:outline-none"
            />
            <p className="text-xs text-slate-400">
              默认位于用户主目录下。完成后可在管理页修改；数据目录固定在 <span className="font-mono">{status.data_dir}</span>。
            </p>
          </section>

          {error && (
            <div className="rounded-lg border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-lg bg-blue-600 py-2.5 text-sm font-medium text-white transition hover:bg-blue-500 disabled:opacity-50 cursor-pointer"
          >
            {loading ? '正在验证 PikPak 并完成初始化…' : '完成首次配置'}
          </button>
        </form>
      </div>
    </div>
  )
}
