import type { Settlement } from '../../api/paymentSettlementApi'
import { formatIstDate } from '../../utils/date'
import { translateKey } from '../../i18n'
import { collectionDaysLabel } from './settlementAging'

/** "Covers 18–20 Nov", or null for legacy settlements recorded before coverage was kept. */
export function coverageLabel(settlement: Settlement): string | null {
  if (!settlement.coversFromDate || !settlement.coversToDate) return null
  return translateKey('settlements:history.covers', { days: collectionDaysLabel(settlement.coversFromDate, settlement.coversToDate) })
}

/** "20 Nov partly cleared" when the last collection day it reached is not fully settled yet. */
export function partialLabel(settlement: Settlement): string {
  if (!settlement.coversToDate) return translateKey('settlements:history.partial')
  return translateKey('settlements:history.partlyCleared', { date: formatIstDate(settlement.coversToDate, { day: 'numeric', month: 'short' }) })
}
