import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MergedTx } from './ledgerGroups'
import { useLedgerFilters } from './useLedgerFilters'

function tx(referenceId: number, type: MergedTx['type'], occurredAt: string): MergedTx {
  return {
    type, referenceId, reference: null, detail: null, amount: 100, runningBalance: 100,
    occurredAt, status: 'ACTIVE', countedInBalance: true, sourceClientId: 1,
  }
}

const transactions = [
  tx(1, 'SALE', '2026-04-02T06:00:00Z'),
  tx(2, 'PAYMENT', '2026-03-31T19:00:00Z'),
  tx(3, 'INTEREST', '2026-03-15T06:00:00Z'),
  tx(4, 'SALE', '2026-02-10T06:00:00Z'),
]

function renderFilters(page = 0, pageSize = 10) {
  return renderHook(() => useLedgerFilters(transactions, transactions, [[transactions[0]]], page, pageSize))
}

describe('useLedgerFilters', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-04-02T19:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows every transaction by default and lists each IST month once', () => {
    const { result } = renderFilters()
    expect(result.current.filtered).toHaveLength(4)
    expect(result.current.availableMonths).toEqual(['April 2026', 'March 2026', 'February 2026'])
  })

  it('defaults the date range to yesterday and today in IST', () => {
    const { result } = renderFilters()
    expect(result.current.fromDate).toBe('2026-04-02')
    expect(result.current.toDate).toBe('2026-04-03')
  })

  it('filters by transaction type across own and sub-account lists', () => {
    const { result } = renderFilters()
    act(() => result.current.setTxTypeFilter('SALE'))
    expect(result.current.filtered.map(t => t.referenceId)).toEqual([1, 4])
    expect(result.current.filteredOwn.map(t => t.referenceId)).toEqual([1, 4])
    expect(result.current.childFilteredTxs[0].map(t => t.referenceId)).toEqual([1])
  })

  it('filters by IST month, so a payment at 00:30 IST on 1 April counts in April', () => {
    const { result } = renderFilters()
    act(() => {
      result.current.setFilterMode('month')
      result.current.setSelectedMonth('April 2026')
    })
    expect(result.current.filtered.map(t => t.referenceId)).toEqual([1, 2])
  })

  it('filters by an inclusive IST date range', () => {
    const { result } = renderFilters()
    act(() => {
      result.current.setFilterMode('range')
      result.current.setFromDate('2026-03-15')
      result.current.setToDate('2026-04-01')
    })
    expect(result.current.filtered.map(t => t.referenceId)).toEqual([2, 3])
  })

  it('pages the filtered list and groups the page by week', () => {
    const { result } = renderFilters(1, 2)
    expect(result.current.pagedFiltered.content.map(t => t.referenceId)).toEqual([3, 4])
    expect(result.current.weekGroups).toHaveLength(2)
  })
})
