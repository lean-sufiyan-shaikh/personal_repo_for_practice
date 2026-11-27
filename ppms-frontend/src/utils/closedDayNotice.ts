import { isAxiosError } from 'axios'
import { translateKey } from '../i18n'
import { useAuthStore } from '../store/authStore'
import { useToastStore } from '../store/toastStore'
import type { ApiErrorBody } from './apiError'
import { formatIstDate } from './date'

export const DAY_CLOSED_CODE = 'DAY_CLOSED'

/**
 * A write refused because its day is closed: the screen shows the error itself, and an owner or admin also gets a
 * toast with the way to re-open that day, since only they can.
 */
export function offerReopenForClosedDay(error: unknown): void {
  if (!isAxiosError<ApiErrorBody>(error) || error.response?.data?.code !== DAY_CLOSED_CODE) return
  const role = useAuthStore.getState().user?.role
  if (role !== 'OWNER' && role !== 'ADMIN') return
  const params = error.response.data.params
  const date = params && typeof params === 'object' && 'date' in params ? String(params.date) : null
  if (!date) return
  useToastStore.getState().addToast(
    translateKey('dayclose:closedDay.toast', { date: formatIstDate(date) }),
    'warning',
    { label: translateKey('dayclose:closedDay.reopen'), to: `/dashboard/day-close?date=${date}` },
  )
}
