import { addDays, istDateString } from '../../utils/date'
import type { MonthDayStatus } from '../../api/monthCloseApi'
import type { StatusTone } from '../../components/StatusBadge'

/** The month before the current one as YYYY-MM: the month usually being closed. */
export function previousMonth(today: string = istDateString()): string {
  return addDays(`${today.slice(0, 7)}-01`, -1).slice(0, 7)
}

export const DAY_TONES: Record<MonthDayStatus, StatusTone> = {
  APPROVED: 'ok',
  CLOSED: 'warn',
  REOPENED: 'warn',
  NOT_CLOSED: 'bad',
}
