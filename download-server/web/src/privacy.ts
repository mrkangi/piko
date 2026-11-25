const MASK = '••••••'

function maskSegment(segment: string): string {
  if (!segment) return segment
  const dot = segment.lastIndexOf('.')
  if (dot > 0 && segment.length - dot <= 12) {
    return MASK + segment.slice(dot)
  }
  return MASK
}

// maskPath hides every path segment while preserving the directory layout and
// a short extension, which keeps the list scannable without leaking names.
export function maskPath(value: string, hidden: boolean): string {
  if (!hidden || !value) return value
  return value
    .split(/([\\/])/)
    .map((part) => (part === '/' || part === '\\' ? part : maskSegment(part)))
    .join('')
}

// maskTextPaths keeps arbitrary error text readable while hiding any full task
// paths that the backend may have embedded in it.
export function maskTextPaths(value: string, paths: string[], hidden: boolean): string {
  if (!hidden || !value) return value
  let masked = value
  const ordered = paths.filter(Boolean).sort((left, right) => right.length - left.length)
  for (const path of ordered) {
    masked = masked.split(path).join(maskPath(path, true))
  }
  return masked
}
