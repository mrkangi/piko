interface Props {
  samples: number[]
  width?: number
  height?: number
  className?: string
}

export function SpeedSparkline({ samples, width = 120, height = 28, className }: Props) {
  const values = samples.length > 1 ? samples : [0, 0]
  const max = Math.max(...values, 1)
  const points = values.map((val, idx) => {
    const x = (idx / (values.length - 1)) * width
    const y = height - (val / max) * (height - 4) - 2
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })
  const areaPoints = `0,${height} ${points.join(' ')} ${width},${height}`

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className={className ? `overflow-visible ${className}` : 'overflow-visible'}>
      <defs>
        <linearGradient id="speedGrad" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="rgb(59 130 246)" stopOpacity="0.35" />
          <stop offset="100%" stopColor="rgb(59 130 246)" stopOpacity="0" />
        </linearGradient>
      </defs>
      <polygon points={areaPoints} fill="url(#speedGrad)" />
      <polyline
        fill="none"
        stroke="rgb(96 165 250)"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
        points={points.join(' ')}
      />
    </svg>
  )
}
