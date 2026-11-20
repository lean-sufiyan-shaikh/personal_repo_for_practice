import { formatIstDate, formatIstDateTime } from '../../utils/date'

export function fmtDate(d: string) {
  return formatIstDate(d)
}

export function fmtDateTime(d: string) {
  return formatIstDateTime(d, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}
