import { useState, useMemo, useCallback } from 'react'
import type { CreditTransaction } from '../../api/creditApi'
import { istDateString, localDateInputValue } from '../../utils/date'
import { pageSlice } from '../../utils/pageSlice'
import { buildWeekGroups } from './ledgerGroups'
import type { MergedTx } from './ledgerGroups'
import { getMonthKey } from './creditDates'

export type FilterMode = 'all' | 'month' | 'range'
export type TxTypeFilter = 'ALL' | 'SALE' | 'PAYMENT' | 'INTEREST'

export function useLedgerFilters(
  transactions: MergedTx[],
  ownTransactions: CreditTransaction[],
  childTransactionsByIndex: MergedTx[][],
  txPage: number,
  txPageSize: number,
) {
  // ── Filter state ──────────────────────────────────────────────────────────
  const [filterMode, setFilterMode] = useState<FilterMode>('all')
  const [txTypeFilter, setTxTypeFilter] = useState<TxTypeFilter>('ALL')
  const [selectedMonth, setSelectedMonth] = useState<string>('')
  const [fromDate, setFromDate]         = useState(() => localDateInputValue(-1))
  const [toDate, setToDate]             = useState(() => localDateInputValue())

  const availableMonths = useMemo(
    () => Array.from(new Set(transactions.map(tx => getMonthKey(tx.occurredAt)))),
    [transactions],
  )

  const applyFilters = useCallback((tx: { type: string; occurredAt: string }): boolean => {
    if (txTypeFilter !== 'ALL' && tx.type !== txTypeFilter) return false
    if (filterMode === 'month' && selectedMonth) return getMonthKey(tx.occurredAt) === selectedMonth
    if (filterMode === 'range') {
      const d = istDateString(new Date(tx.occurredAt))
      if (fromDate && d < fromDate) return false
      if (toDate   && d > toDate)   return false
    }
    return true
  }, [txTypeFilter, filterMode, selectedMonth, fromDate, toDate])

  const filtered = useMemo(() => transactions.filter(applyFilters), [transactions, applyFilters])
  const pagedFiltered = useMemo(() => pageSlice(filtered, txPage, txPageSize), [filtered, txPage, txPageSize])
  const weekGroups = useMemo(() => buildWeekGroups(pagedFiltered.content), [pagedFiltered])

  const childFilteredTxs = childTransactionsByIndex.map(childTxs => childTxs.filter(applyFilters))
  const filteredOwn = ownTransactions.filter(applyFilters)

  return {
    filterMode, setFilterMode, txTypeFilter, setTxTypeFilter, selectedMonth, setSelectedMonth,
    fromDate, setFromDate, toDate, setToDate, availableMonths,
    filtered, pagedFiltered, weekGroups, childFilteredTxs, filteredOwn,
  }
}

export type LedgerFilters = ReturnType<typeof useLedgerFilters>
