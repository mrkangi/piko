import { useState, type FormEvent } from 'react'
import { CloudDownload, Lock, ShieldCheck, User } from 'lucide-react'
import { login } from '../api'
import type { AuthUser } from '../types'
import { Button, Card, Field, inputClassName } from './ui'

interface Props {
  onAuthenticated: (user: AuthUser) => void
}

export function LoginView({ onAuthenticated }: Props) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError('')
    setLoading(true)
    try {
      const user = await login(username.trim(), password)
      setPassword('')
      onAuthenticated(user)
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4 py-10">
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute -left-32 top-1/4 h-80 w-80 rounded-full bg-accent/8 blur-3xl" />
        <div className="absolute -right-24 bottom-1/4 h-72 w-72 rounded-full bg-cyan-500/6 blur-3xl" />
      </div>

      <div className="relative grid w-full max-w-4xl gap-6 lg:grid-cols-[1fr_400px] lg:items-center">
        <div className="hidden lg:block">
          <div className="mb-5 flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl border border-accent/30 bg-accent/12 text-accent">
              <CloudDownload className="h-6 w-6" />
            </div>
            <div>
              <div className="text-lg font-semibold tracking-tight">Piko Download Server</div>
              <div className="text-xs text-ink-3">PikPak 远程目录同步与下载管理台</div>
            </div>
          </div>
          <h1 className="text-2xl font-semibold leading-tight tracking-tight text-ink">
            把 PikPak 目录变成
            <br />
            服务器上的自动下载队列。
          </h1>
          <p className="mt-3 max-w-md text-sm leading-6 text-ink-3">
            服务端负责扫描、分片下载、校验与交付，浏览器只需要配置来源和查看进度。文件不经过浏览器，也不覆盖本地已有文件。
          </p>
          <div className="mt-6 flex flex-wrap gap-2 text-[11px] text-ink-3">
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface-1/60 px-2.5 py-1.5">
              <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
              SHA256 校验交付
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-surface-1/60 px-2.5 py-1.5">
              <Lock className="h-3.5 w-3.5 text-accent" />
              凭据本地加密
            </span>
          </div>
        </div>

        <Card className="p-5 sm:p-6">
          <div className="mb-5 flex items-center gap-2.5 lg:hidden">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg border border-accent/30 bg-accent/12 text-accent">
              <CloudDownload className="h-5 w-5" />
            </div>
            <div>
              <div className="text-sm font-semibold">Piko Download Server</div>
              <div className="text-[11px] text-ink-3">管理员登录</div>
            </div>
          </div>
          <div className="mb-5 hidden lg:block">
            <h2 className="text-base font-semibold">登录控制面板</h2>
            <p className="mt-1 text-xs text-ink-3">使用首次配置时创建的管理员账号。</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <Field label="管理账号">
              <div className="relative">
                <User className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-3" />
                <input
                  type="text"
                  autoComplete="username"
                  required
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  className={`${inputClassName} pl-9`}
                />
              </div>
            </Field>

            <Field label="密码">
              <div className="relative">
                <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-3" />
                <input
                  type="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  className={`${inputClassName} pl-9`}
                />
              </div>
            </Field>

            {error && <div className="rounded-lg border border-rose-500/30 bg-rose-500/8 px-3 py-2 text-xs text-rose-300">{error}</div>}

            <Button type="submit" variant="primary" size="md" loading={loading} className="w-full">
              {loading ? '登录中…' : '登录控制面板'}
            </Button>
          </form>
        </Card>
      </div>
    </div>
  )
}
