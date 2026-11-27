import { useQuery, useQueryClient } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'

/** The operators, reliefs, shares and timeline of a shift, and a refresh of everything a change on the shift moves. */
export function useShiftOperations(pumpId: number, shiftId: number) {
  const queryClient = useQueryClient()
  const query = useQuery({
    queryKey: ['shiftOperations', pumpId, shiftId],
    queryFn: () => shiftApi.getOperations(pumpId, shiftId),
  })
  const refreshAfterChange = () => {
    queryClient.invalidateQueries({ queryKey: ['shiftOperations', pumpId, shiftId] })
    queryClient.invalidateQueries({ queryKey: ['shift', pumpId, shiftId] })
    queryClient.invalidateQueries({ queryKey: ['activeShifts', pumpId] })
  }
  return { ...query, refreshAfterChange }
}
