import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'
import { SkeletonRows } from '../../../components/Skeleton'
import { localDateInputValue } from '../../../utils/date'
import { parseApiError } from '../../../utils/apiError'
import { HistoryRow } from './HistoryRow'
import { HISTORY_COLUMNS } from './historyColumns'
import { PAGE_SIZE } from '../shiftLabels'
import { DataTable } from '../../../components/DataTable'
import { codeLabel } from '../../../constants/labels'

// ── All Shifts (paginated + date range filter) ────────────────────────────────

/** initialFrom/initialTo/fuelType come from the address, e.g. a "View shifts" link on the P&L report. */
export function AllShiftsHistory({ pumpId, initialFrom, initialTo, fuelType, onClearFuel }: {
  pumpId: number
  initialFrom?: string
  initialTo?: string
  fuelType?: string
  onClearFuel?: () => void
}) {
  const [page,     setPage]     = useState(0)
  const [fromDate, setFromDate] = useState(() => initialFrom ?? localDateInputValue(-1))
  const [toDate,   setToDate]   = useState(() => initialTo ?? localDateInputValue())

  const { data, isLoading, error } = useQuery({
    queryKey: ['shiftHistory', pumpId, 'all', fromDate, toDate, fuelType, page],
    queryFn:  () => shiftApi.getShiftHistoryPage(pumpId, { from: fromDate || undefined, to: toDate || undefined, fuelType }, page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })

  const paged         = data?.content ?? []
  const totalElements = data?.page.totalElements ?? 0
  const totalPages    = Math.max(1, data?.page.totalPages ?? 1)
  const safePage      = Math.min(page, totalPages - 1)

  const clearFilter = () => { setFromDate(''); setToDate(''); setPage(0) }

  return (
    <div>
      {/* Date range filter bar */}
      <div className="flex flex-wrap items-center gap-3 px-6 py-3 border-t border-border bg-surface-sunken">
        <span className="text-xs font-medium text-ink-3">Filter by date:</span>
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <input aria-label="From date"
            type="date"
            value={fromDate}
            onChange={(e) => { setFromDate(e.target.value); setPage(0) }}
            className="flex-1 min-w-[8.5rem] border border-control rounded-control px-2.5 py-1.5 text-xs"
          />
          <span className="text-ink-3 text-xs">to</span>
          <input aria-label="To date"
            type="date"
            value={toDate}
            onChange={(e) => { setToDate(e.target.value); setPage(0) }}
            className="flex-1 min-w-[8.5rem] border border-control rounded-control px-2.5 py-1.5 text-xs"
          />
        </div>
        {fuelType && onClearFuel && (
          <button type="button" onClick={onClearFuel} className="ui-chip" aria-pressed="true"
            aria-label={`Showing ${codeLabel(fuelType)} shifts only. Show all fuels`}>
            {codeLabel(fuelType)} only ×
          </button>
        )}
        {(fromDate || toDate) && (
          <button
            onClick={clearFilter}
            className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-ink-3 hover:text-ink underline"
          >
            Clear
          </button>
        )}
        <span className="ml-auto text-xs text-ink-3">
          {totalElements} shift{totalElements !== 1 ? 's' : ''}
          {(fromDate || toDate || fuelType) ? ' (filtered)' : ''}
        </span>
      </div>

      {isLoading ? (
        <div className="px-5 py-4 border-t border-border"><SkeletonRows count={4} /></div>
      ) : error ? (
        <div className="px-6 py-4 border-t border-border">
          <p role="alert" className="ui-alert ui-alert-error text-sm">{parseApiError(error, 'Failed to load shift history')}</p>
        </div>
      ) : (
        <DataTable
          caption="All shifts"
          captionHidden
          columns={HISTORY_COLUMNS}
          rows={paged}
          rowKey={(shift) => shift.id}
          renderRow={(shift) => <HistoryRow shift={shift} />}
          empty={<p className="ui-empty px-6 py-6">No shifts match the selected date range.</p>}
          className="rounded-none border-x-0 border-b-0"
        />
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-6 py-3 border-t border-border bg-surface-sunken">
          <span className="text-xs text-ink-3">
            Page {safePage + 1} of {totalPages}
            <span className="ml-2 text-ink-3">
              (showing {safePage * PAGE_SIZE + 1}–{Math.min((safePage + 1) * PAGE_SIZE, totalElements)} of {totalElements})
            </span>
          </span>
          <div className="flex gap-1">
            <button aria-label="First page"
              onClick={() => setPage(0)}
              disabled={safePage === 0}
              className="ui-btn ui-btn-secondary min-h-0 px-2.5 py-1 text-xs disabled:opacity-30"
            >
              «
            </button>
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={safePage === 0}
              className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs disabled:opacity-30"
            >
              Prev
            </button>
            {/* Page number pills — show window of 5 */}
            {Array.from({ length: totalPages }, (_, i) => i)
              .filter((i) => Math.abs(i - safePage) <= 2)
              .map((i) => (
                <button
                  key={i}
                  onClick={() => setPage(i)}
                  className={`ui-btn min-h-0 px-3 py-1 text-xs rounded-control border transition-colors ${
                    i === safePage
                      ? 'bg-primary border-primary text-on-primary'
                      : 'border-border hover:bg-surface-sunken text-ink-2'
                  }`}
                >
                  {i + 1}
                </button>
              ))}
            <button
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={safePage === totalPages - 1}
              className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs disabled:opacity-30"
            >
              Next
            </button>
            <button aria-label="Last page"
              onClick={() => setPage(totalPages - 1)}
              disabled={safePage === totalPages - 1}
              className="ui-btn ui-btn-secondary min-h-0 px-2.5 py-1 text-xs disabled:opacity-30"
            >
              »
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
