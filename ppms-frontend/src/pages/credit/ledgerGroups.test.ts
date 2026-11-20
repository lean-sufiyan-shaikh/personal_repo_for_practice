import { describe, expect, it } from 'vitest'
import type { CreditTransaction } from '../../api/creditApi'
import { buildWeekGroups } from './ledgerGroups'

function tx(referenceId: number, occurredAt: string): CreditTransaction {
  return {
    type: 'SALE', referenceId, reference: null, detail: null, amount: 10,
    runningBalance: 10, occurredAt, status: 'ACTIVE', countedInBalance: true,
  }
}

describe('buildWeekGroups', () => {
  it('groups consecutive transactions of the same week in order', () => {
    const groups = buildWeekGroups([
      tx(1, '2026-03-20T06:00:00Z'),
      tx(2, '2026-03-17T06:00:00Z'),
      tx(3, '2026-03-12T06:00:00Z'),
    ])
    expect(groups.map(g => g.label)).toEqual(['16 Mar 2026 – 22 Mar 2026', '09 Mar 2026 – 15 Mar 2026'])
    expect(groups[0].items.map(t => t.referenceId)).toEqual([1, 2])
    expect(groups[1].items.map(t => t.referenceId)).toEqual([3])
  })

  it('returns no groups for no transactions', () => {
    expect(buildWeekGroups([])).toEqual([])
  })
})
