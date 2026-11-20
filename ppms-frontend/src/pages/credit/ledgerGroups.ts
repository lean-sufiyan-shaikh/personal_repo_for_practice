import type { CreditTransaction } from '../../api/creditApi'
import { getWeekKey } from './creditDates'

// ─── Helpers for week grouping ───────────────────────────────────────────────

// Transaction in the merged parent view, carrying which sub-account it came from
export type MergedTx = CreditTransaction & { subAccountName?: string; sourceClientId: number }

export function buildWeekGroups<T extends CreditTransaction>(txs: T[]): Array<{ label: string; items: T[] }> {
  const groups: Array<{ label: string; items: T[] }> = []
  txs.forEach(tx => {
    const label = getWeekKey(tx.occurredAt)
    const last = groups[groups.length - 1]
    if (last && last.label === label) { last.items.push(tx) }
    else { groups.push({ label, items: [tx] }) }
  })
  return groups
}
