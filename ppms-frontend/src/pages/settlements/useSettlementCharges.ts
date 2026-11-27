import { useQuery } from '@tanstack/react-query'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { SettlementChargesQuery } from '../../api/paymentSettlementApi'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'

const CHARGES_DELAY_MS = 400
const MIN_AMOUNT = 0.01

/** The server's charges for the amount being typed, asked for once typing pauses; undefined until they match the input. */
export function useSettlementCharges(pumpId: number, query: SettlementChargesQuery) {
  const currentKey = JSON.stringify(query)
  const debouncedKey = useDebouncedValue(currentKey, CHARGES_DELAY_MS)
  const debouncedQuery = JSON.parse(debouncedKey) as SettlementChargesQuery

  const charges = useQuery({
    queryKey: ['settlement-charges', pumpId, debouncedKey],
    queryFn: () => paymentSettlementApi.getCharges(pumpId, debouncedQuery),
    enabled: debouncedQuery.amount >= MIN_AMOUNT,
  })

  const matchesInput = debouncedKey === currentKey && query.amount >= MIN_AMOUNT
  return {
    charges: matchesInput ? charges.data : undefined,
    error: matchesInput ? charges.error : null,
  }
}
