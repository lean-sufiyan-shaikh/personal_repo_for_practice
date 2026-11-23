

export const MAX_CHART_DAYS = 30

// ── Live shift board row ───────────────────────────────────────────────────────

export function timeOpen(startTime: string, now: number = Date.now()): string {
  const mins = Math.max(0, Math.floor((now - new Date(startTime).getTime()) / 60_000))
  if (mins < 60) return `${mins}m`
  return `${Math.floor(mins / 60)}h ${mins % 60}m`
}
