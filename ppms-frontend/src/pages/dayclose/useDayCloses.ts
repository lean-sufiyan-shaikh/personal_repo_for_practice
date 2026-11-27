import { useQuery, useQueryClient } from '@tanstack/react-query'
import { dayCloseApi } from '../../api/dayCloseApi'
import type { DayCloseListItem, DayCloseStatus } from '../../api/dayCloseApi'
import { localDateInputValue } from '../../utils/date'
import { isLocked } from './dayCloseText'

export const RECENT_DAYS_SIZE = 10

export function useRecentDayCloses(pumpId: number | null) {
  return useQuery({
    queryKey: ['dayCloses', pumpId, 'recent'],
    queryFn: () => dayCloseApi.list(pumpId!, 0, RECENT_DAYS_SIZE),
    enabled: pumpId !== null,
  })
}

export function statusOn(days: DayCloseListItem[], date: string): DayCloseStatus | null {
  return days.find((day) => day.businessDate === date)?.status ?? null
}

/** Yesterday until it is closed, then today; null once both are closed. */
export function nextDayToClose(days: DayCloseListItem[]): string | null {
  const yesterday = localDateInputValue(-1)
  const today = localDateInputValue()
  if (!isLocked(statusOn(days, yesterday))) return yesterday
  if (!isLocked(statusOn(days, today))) return today
  return null
}

/** Everything about day closes can change after a close, approval, comment or re-open, so all of it is refetched. */
export function useRefreshDayCloses(pumpId: number) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['dayCloses', pumpId] })
    void queryClient.invalidateQueries({ queryKey: ['dayClose', pumpId] })
    void queryClient.invalidateQueries({ queryKey: ['dayClosePreview', pumpId] })
    void queryClient.invalidateQueries({ queryKey: ['attention'] })
  }
}
