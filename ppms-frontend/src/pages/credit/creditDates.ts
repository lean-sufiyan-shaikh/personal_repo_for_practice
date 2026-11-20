import { addDays, formatIstDate, istDateString } from '../../utils/date'

export function getWeekKey(iso: string): string {
  const istDate = istDateString(new Date(iso))
  const dayOfWeek = new Date(`${istDate}T00:00:00Z`).getUTCDay()
  const monday = addDays(istDate, dayOfWeek === 0 ? -6 : 1 - dayOfWeek)
  const sunday = addDays(monday, 6)
  const fmt = (date: string) =>
    formatIstDate(date, { day: '2-digit', month: 'short', year: 'numeric' })
  return `${fmt(monday)} – ${fmt(sunday)}`
}

export function getMonthKey(iso: string): string {
  return formatIstDate(iso, { month: 'long', year: 'numeric' })
}
