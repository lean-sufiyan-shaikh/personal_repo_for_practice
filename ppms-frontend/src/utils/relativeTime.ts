import { formatIstDate } from './date'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** "just now", "5m ago", "2h ago", "3d ago", then the date ("12 Nov") after a week. */
export function relativeTime(isoInstant: string, now: Date = new Date()): string {
  const elapsed = now.getTime() - new Date(isoInstant).getTime()
  if (elapsed < MINUTE) return 'just now'
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)}m ago`
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)}h ago`
  if (elapsed < 7 * DAY) return `${Math.floor(elapsed / DAY)}d ago`
  return formatIstDate(isoInstant, { day: 'numeric', month: 'short' })
}
