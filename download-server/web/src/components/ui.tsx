import { useEffect, type ComponentPropsWithoutRef, type ReactNode } from 'react'
import { Loader2, X } from 'lucide-react'
import type { StatusTone } from '../format'

export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter(Boolean).join(' ')
}

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'soft'
type ButtonSize = 'sm' | 'md' | 'icon' | 'icon-sm'

const BUTTON_VARIANTS: Record<ButtonVariant, string> = {
  primary: 'bg-accent text-[#06101f] hover:brightness-110 active:brightness-95',
  secondary: 'border border-line bg-surface-2 text-ink hover:bg-surface-3',
  ghost: 'text-ink-2 hover:bg-surface-2 hover:text-ink',
  danger: 'border border-rose-500/30 bg-rose-500/10 text-rose-300 hover:bg-rose-500/20',
  soft: 'border border-accent/30 bg-accent/12 text-accent hover:bg-accent/20',
}

const BUTTON_SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 gap-1.5 px-3 text-xs',
  md: 'h-10 gap-2 px-4 text-sm',
  icon: 'h-9 w-9',
  'icon-sm': 'h-8 w-8',
}

interface ButtonProps extends ComponentPropsWithoutRef<'button'> {
  variant?: ButtonVariant
  size?: ButtonSize
  loading?: boolean
}

export function Button({
  variant = 'secondary',
  size = 'sm',
  loading = false,
  className,
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={cn(
        'inline-flex shrink-0 cursor-pointer items-center justify-center rounded-lg font-medium transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-45',
        BUTTON_VARIANTS[variant],
        BUTTON_SIZES[size],
        className,
      )}
      {...rest}
    >
      {loading && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
      {children}
    </button>
  )
}

const BADGE_TONES: Record<StatusTone, string> = {
  neutral: 'border-line-strong bg-surface-2 text-ink-2',
  active: 'border-accent/35 bg-accent/12 text-accent',
  success: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300',
  wait: 'border-amber-500/30 bg-amber-500/10 text-amber-300',
  danger: 'border-rose-500/35 bg-rose-500/10 text-rose-300',
  info: 'border-cyan-500/30 bg-cyan-500/10 text-cyan-300',
}

export function Badge({
  tone = 'neutral',
  children,
  className,
}: {
  tone?: StatusTone
  children: ReactNode
  className?: string
}) {
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-medium leading-4',
        BADGE_TONES[tone],
        className,
      )}
    >
      {children}
    </span>
  )
}

const DOT_TONES: Record<StatusTone, string> = {
  neutral: 'bg-ink-3',
  active: 'bg-accent',
  success: 'bg-emerald-400',
  wait: 'bg-amber-400',
  danger: 'bg-rose-400',
  info: 'bg-cyan-400',
}

export function StatusDot({ tone = 'neutral', pulse = false, className }: { tone?: StatusTone; pulse?: boolean; className?: string }) {
  return (
    <span className={cn('inline-flex h-2 w-2 shrink-0 rounded-full', DOT_TONES[tone], pulse && 'animate-pulse', className)} />
  )
}

export function Card({ className, children }: { className?: string; children: ReactNode }) {
  return <section className={cn('rounded-xl border border-line bg-surface-1 surface-inset', className)}>{children}</section>
}

export function CardHeader({
  title,
  description,
  icon,
  actions,
  className,
}: {
  title: ReactNode
  description?: ReactNode
  icon?: ReactNode
  actions?: ReactNode
  className?: string
}) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-3 border-b border-line px-4 py-3.5 sm:px-5', className)}>
      <div className="flex min-w-0 items-start gap-3">
        {icon && (
          <div className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2 text-ink-2">
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-ink">{title}</h2>
          {description && <p className="mt-0.5 text-xs leading-5 text-ink-3">{description}</p>}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function Field({
  label,
  hint,
  children,
  className,
}: {
  label: ReactNode
  hint?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <label className={cn('block space-y-1.5', className)}>
      <span className="block text-xs font-medium text-ink-2">{label}</span>
      {children}
      {hint && <span className="block text-[11px] leading-4 text-ink-3">{hint}</span>}
    </label>
  )
}

export const inputClassName =
  'w-full rounded-lg border border-line bg-canvas px-3 py-2 text-sm text-ink placeholder:text-ink-3 transition-colors focus:border-accent focus:outline-none'

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode
  title: string
  description?: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 px-6 py-14 text-center">
      {icon && <div className="mb-1 flex h-11 w-11 items-center justify-center rounded-xl border border-line bg-surface-2 text-ink-3">{icon}</div>}
      <div className="text-sm font-medium text-ink-2">{title}</div>
      {description && <div className="max-w-md text-xs leading-5 text-ink-3">{description}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}

export function ProgressBar({
  value,
  className,
  barClassName,
}: {
  value: number
  className?: string
  barClassName?: string
}) {
  const width = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0))
  return (
    <div className={cn('h-1.5 w-full overflow-hidden rounded-full bg-surface-3', className)}>
      <div className={cn('h-full rounded-full transition-[width] duration-300', barClassName || 'bg-accent')} style={{ width: `${width}%` }} />
    </div>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange,
  className,
}: {
  value: T
  options: Array<{ key: T; label: string; count?: number }>
  onChange: (value: T) => void
  className?: string
}) {
  return (
    <div className={cn('no-scrollbar flex max-w-full items-center gap-1 overflow-x-auto rounded-lg border border-line bg-surface-1 p-1', className)}>
      {options.map((option) => {
        const active = option.key === value
        return (
          <button
            key={option.key || 'all'}
            type="button"
            onClick={() => onChange(option.key)}
            aria-pressed={active}
            className={cn(
              'inline-flex h-7 shrink-0 cursor-pointer items-center gap-1.5 rounded-md px-2.5 text-xs font-medium transition-colors',
              active ? 'bg-accent/15 text-accent' : 'text-ink-3 hover:bg-surface-2 hover:text-ink-2',
            )}
          >
            {option.label}
            {option.count != null && (
              <span className={cn('rounded px-1 text-[10px] tabular-nums', active ? 'bg-accent/15' : 'bg-surface-2')}>{option.count}</span>
            )}
          </button>
        )
      })}
    </div>
  )
}

export function Modal({
  open,
  onClose,
  title,
  description,
  icon,
  children,
  footer,
  size = 'md',
  busy = false,
}: {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  icon?: ReactNode
  children: ReactNode
  footer?: ReactNode
  size?: 'md' | 'lg' | 'xl'
  busy?: boolean
}) {
  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKey)
    }
  }, [open, busy, onClose])

  if (!open) return null

  const widths = { md: 'sm:max-w-lg', lg: 'sm:max-w-2xl', xl: 'sm:max-w-4xl' }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/65 backdrop-blur-sm sm:items-center sm:p-4"
      onMouseDown={() => {
        if (!busy) onClose()
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        className={cn('flex max-h-[92dvh] w-full flex-col overflow-hidden rounded-t-2xl border border-line bg-surface-1 shadow-2xl sm:rounded-2xl', widths[size])}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="mx-auto mt-2 h-1 w-9 rounded-full bg-line-strong sm:hidden" />
        <div className="flex items-start justify-between gap-3 border-b border-line px-4 py-3.5 sm:px-5">
          <div className="flex min-w-0 items-start gap-3">
            {icon && <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-line bg-surface-2 text-ink-2">{icon}</div>}
            <div className="min-w-0">
              <h2 className="truncate text-sm font-semibold text-ink">{title}</h2>
              {description && <p className="mt-0.5 text-xs leading-5 text-ink-3">{description}</p>}
            </div>
          </div>
          <Button variant="ghost" size="icon-sm" onClick={onClose} disabled={busy} aria-label="关闭">
            <X className="h-4 w-4" />
          </Button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 py-4 sm:px-5">{children}</div>
        {footer && <div className="flex flex-wrap items-center justify-end gap-2 border-t border-line bg-canvas/40 px-4 py-3 sm:px-5">{footer}</div>}
      </div>
    </div>
  )
}

export function Spinner({ label }: { label?: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-xs text-ink-3">
      <Loader2 className="h-3.5 w-3.5 animate-spin" />
      {label}
    </span>
  )
}
