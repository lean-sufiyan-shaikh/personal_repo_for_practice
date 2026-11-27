import { addDays } from '../../../utils/date'

export type CountDay = 'today' | 'yesterday' | 'earlier'

/** Mirrors the server: yesterday counts late until the cut-off; anything older is back-dated (owner/admin, with a reason). */
export type CountTiming = 'ON_THE_DAY' | 'NEXT_MORNING' | 'BACK_DATED'

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export function countTimingOf(date: string, today: string, yesterdayCountOpen: boolean): CountTiming {
  if (date >= today) return 'ON_THE_DAY'
  if (date === addDays(today, -1) && yesterdayCountOpen) return 'NEXT_MORNING'
  return 'BACK_DATED'
}

/** Which choice a preset date (e.g. ?countFor= from the Day Close checklist) selects; anything unusable means today. */
export function countDayOf(date: string | null | undefined, today: string): CountDay {
  if (!date || !ISO_DATE.test(date) || date >= today) return 'today'
  return date === addDays(today, -1) ? 'yesterday' : 'earlier'
}

/** "10:00:00" → "10:00" */
export function shortTime(time: string): string {
  return time.slice(0, 5)
}
