import { useState, type FormEvent } from 'react'
import type { ReactNode } from 'react'
import { CloudDownload, FolderOpen, KeyRound, Lock, User } from 'lucide-react'
import { completeSetup } from '../api'
import type { AuthUser, SetupStatus } from '../types'
import { Button, Card, Field, inputClassName } from './ui'

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
    <div className="min-h-dvh bg-canvas px-4 py-8 sm:py-12">
      <div className="mx-auto w-full max-w-2xl">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-accent/30 bg-accent/12 text-accent">
            <CloudDownload className="h-6 w-6" />
          </div>
          <div>
            <h1 className="text-lg font-semibold tracking-tight text-ink">首次配置</h1>
            <p className="text-xs text-ink-3">配置管理员、PikPak 账号和本地下载目录，完成后即可进入控制面板。</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <Card className="p-4 sm:p-5">
            <StepTitle index={1} title="首次配置令牌" icon={<KeyRound className="h-4 w-4" />} />
            <p className="mb-3 text-[11px] leading-5 text-ink-3">
              服务启动时已在控制台打印 Setup Token。默认监听 <span className="font-mono text-ink-2">0.0.0.0</span>，必须凭该令牌完成首次初始化。
            </p>
            <Field label="Setup Token">
              <input
                type="password"
                required
                value={token}
                onChange={(event) => setToken(event.target.value)}
                placeholder="粘贴控制台打印的 Setup Token"
                className={`${inputClassName} font-mono text-xs`}
              />
            </Field>
          </Card>

          <Card className="p-4 sm:p-5">
            <StepTitle index={2} title="管理员与 PikPak 账号" icon={<User className="h-4 w-4" />} />
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-3">
                <div className="text-[11px] font-medium text-ink-2">管理员账号</div>
                <Field label="用户名">
                  <input
                    type="text"
                    required
                    value={adminUser}
                    onChange={(event) => setAdminUser(event.target.value)}
                    placeholder="管理员用户名"
                    className={inputClassName}
                  />
                </Field>
                <Field label="密码">
                  <input
                    type="password"
                    required
                    minLength={8}
                    value={adminPass}
                    onChange={(event) => setAdminPass(event.target.value)}
                    placeholder="至少 8 位"
                    className={inputClassName}
                  />
                </Field>
                <Field label="确认密码">
                  <input
                    type="password"
                    required
                    minLength={8}
                    value={adminPassConfirm}
                    onChange={(event) => setAdminPassConfirm(event.target.value)}
                    placeholder="再次输入管理员密码"
                    className={inputClassName}
                  />
                </Field>
              </div>

              <div className="space-y-3">
                <div className="text-[11px] font-medium text-ink-2">PikPak 账号</div>
                <Field label="用户名 / 邮箱">
                  <input
                    type="text"
                    required
                    value={pikpakUser}
                    onChange={(event) => setPikpakUser(event.target.value)}
                    placeholder="PikPak 用户名 / 邮箱"
                    className={inputClassName}
                  />
                </Field>
                <Field label="密码">
                  <div className="relative">
                    <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-3" />
                    <input
                      type="password"
                      required
                      value={pikpakPass}
                      onChange={(event) => setPikpakPass(event.target.value)}
                      placeholder="PikPak 密码"
                      className={`${inputClassName} pl-9`}
                    />
                  </div>
                </Field>
                <div className="rounded-lg border border-line bg-canvas/40 px-2.5 py-2 text-[11px] leading-5 text-ink-3">
                  密码会使用本地密钥加密后存入数据目录，不会写进日志。
                </div>
              </div>
            </div>
          </Card>

          <Card className="p-4 sm:p-5">
            <StepTitle index={3} title="本地下载目录" icon={<FolderOpen className="h-4 w-4" />} />
            <Field label="服务端下载根目录" hint={`数据目录固定在 ${status.data_dir}，完成后可在管理页修改下载目录。`}>
              <input
                type="text"
                required
                value={downloadDir}
                onChange={(event) => setDownloadDir(event.target.value)}
                className={`${inputClassName} font-mono text-xs`}
              />
            </Field>
          </Card>

          {error && <div className="rounded-lg border border-rose-500/30 bg-rose-500/8 px-3 py-2 text-xs text-rose-300">{error}</div>}

          <div className="pt-1">
            <Button type="submit" variant="primary" size="md" loading={loading} className="w-full shadow-xl">
              {loading ? '正在验证 PikPak 并完成初始化…' : '完成首次配置'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}

function StepTitle({ index, title, icon }: { index: number; title: string; icon: ReactNode }) {
  return (
    <div className="mb-3 flex items-center gap-2.5">
      <span className="flex h-6 w-6 items-center justify-center rounded-full bg-accent/15 text-[11px] font-semibold text-accent tabular-nums">{index}</span>
      <span className="flex items-center gap-1.5 text-sm font-semibold text-ink">
        {icon}
        {title}
      </span>
    </div>
  )
}
