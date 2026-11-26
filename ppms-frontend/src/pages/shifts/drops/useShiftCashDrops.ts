import { useQuery } from '@tanstack/react-query'
import { cashApi } from '../../../api/cashApi'
import { roundToPaise } from '../../../utils/money'

/** The shift's cash drops, newest first, with the confirmed total and the drops still waiting for a decision. */
export function useShiftCashDrops(pumpId: number, shiftId: number, enabled = true) {
  const { data: drops = [], isLoading, isError } = useQuery({
    queryKey: ['cashDrops', pumpId, shiftId],
    queryFn: () => cashApi.getShiftDrops(pumpId, shiftId),
    enabled,
    refetchInterval: 30_000,
  })
  const confirmedTotal = roundToPaise(drops.filter((drop) => drop.status === 'CONFIRMED').reduce((sum, drop) => sum + drop.amount, 0))
  const pendingDrops = drops.filter((drop) => drop.status === 'PENDING')
  return { drops, confirmedTotal, pendingDrops, isLoading, isError }
}
