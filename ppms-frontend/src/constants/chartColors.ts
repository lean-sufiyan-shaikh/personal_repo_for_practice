// Mirrors the --color-primary, --color-ok, --color-bad, --color-warn, --color-info, --color-border and --color-ink-3
// tokens in index.css; recharts needs plain colour strings.
export const CHART_COLORS = {
  primary: '#0b5394',
  ok: '#17663a',
  bad: '#b42318',
  warn: '#8a4b00',
  info: '#1f4e8c',
  grid: '#d5dae1',
  axis: '#5a6472',
}

export const CHART_TOOLTIP_STYLE = {
  fontSize: '0.75rem',
  borderRadius: '8px',
  border: `1px solid ${CHART_COLORS.grid}`,
  padding: '0.5rem 0.75rem',
}

export function compactRupees(value: number): string {
  const size = Math.abs(value)
  if (size >= 100_000) return `₹${(value / 100_000).toFixed(1)}L`
  if (size >= 1_000) return `₹${(value / 1_000).toFixed(0)}k`
  return `₹${value}`
}
