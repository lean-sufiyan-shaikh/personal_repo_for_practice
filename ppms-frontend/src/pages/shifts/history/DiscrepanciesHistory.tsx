import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { shiftApi } from '../../../api/shiftApi'
import { useAuthStore } from '../../../store/authStore'
import type { Shift } from '../../../types/shift'
import { ResolveDiscrepancyModal } from '../modals/ResolveDiscrepancyModal'
import { SkeletonRows } from '../../../components/Skeleton'
import { parseApiError } from '../../../utils/apiError'
import { AWAITING_OWNER_APPROVAL, PAGE_SIZE, fmtAmt, RESOLUTION_LABELS, discrepancyAction, investigationLabel, statusBadge } from '../shiftLabels'
import { ScrollRegion } from '../../../components/ScrollRegion'
import { FULL_HISTORY_QUERY_OPTIONS } from '../../../queryClient'
import type { UserRole } from '../../../types/auth'
import { DiscrepancyBadge } from '../components/DiscrepancyBadge'

function DiscrepancyStatus({ shift }: { shift: Shift }) {
  const badge = statusBadge(shift.status)
  const investigation = investigationLabel(shift)
  return (
    <>
      {shift.discrepancyResolution ? (
        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${
          shift.discrepancyResolution === 'CASH_RECOVERY'   ? 'bg-green-100 text-green-700' :
          shift.discrepancyResolution === 'SALARY_DEDUCTION'? 'bg-blue-100 text-blue-700' :
          shift.discrepancyResolution === 'WAIVED'          ? 'bg-slate-100 text-slate-600' :
                                                              'bg-orange-100 text-orange-700'
        }`}>
          {RESOLUTION_LABELS[shift.discrepancyResolution] ?? shift.discrepancyResolution}
        </span>
      ) : (
        <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${badge.color}`}>{badge.label}</span>
      )}
      {investigation && (
        <span className="block text-xs text-orange-600 font-medium mt-1">{investigation}</span>
      )}
    </>
  )
}

function DiscrepancyAction({ shift, role, onOpen, className = '' }: {
  shift: Shift; role: UserRole | undefined; onOpen: (shift: Shift) => void; className?: string
}) {
  const action = discrepancyAction(shift, role)
  if (action === 'resolve') {
    return (
      <button type="button" onClick={() => onOpen(shift)}
        aria-label={`Resolve discrepancy for shift ${shift.id}`}
        className={`ui-btn ui-btn-danger ${className}`}>
        Resolve
      </button>
    )
  }
  if (action === 'approve') {
    return (
      <button type="button" onClick={() => onOpen(shift)}
        aria-label={`Approve discrepancy for shift ${shift.id}`}
        className={`ui-btn ui-btn-warning ${className}`}>
        Approve
      </button>
    )
  }
  if (action === 'awaiting-owner') {
    return <span className="text-xs text-orange-600 font-medium">{AWAITING_OWNER_APPROVAL}</span>
  }
  return null
}

export function DiscrepanciesHistory({ pumpId }: { pumpId: number }) {
  const [page,     setPage]     = useState(0)
  const [fromDate, setFromDate] = useState('')
  const [toDate,   setToDate]   = useState('')
  const [typeFilter, setTypeFilter] = useState<'ALL' | 'SHORT' | 'OVER'>('ALL')
  const [resolvingShift, setResolvingShift] = useState<Shift | null>(null)
  const role = useAuthStore((state) => state.user?.role)

  const { data: filtered = [], isLoading, error } = useQuery({
    queryKey: ['shiftHistory', pumpId, 'discrepancies', fromDate, toDate, typeFilter],
    queryFn:  () => shiftApi.getShiftHistory(pumpId, {
      from: fromDate || undefined,
      to: toDate || undefined,
      discrepanciesOnly: true,
      discrepancyType: typeFilter === 'ALL' ? undefined : typeFilter,
    }),
    ...FULL_HISTORY_QUERY_OPTIONS,
  })
  const hasFilter = !!fromDate || !!toDate || typeFilter !== 'ALL'

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage   = Math.min(page, totalPages - 1)
  const paged      = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const clearFilter = () => { setFromDate(''); setToDate(''); setTypeFilter('ALL'); setPage(0) }

  const totalShort = filtered.filter(s => s.discrepancyType === 'SHORT').reduce((sum, s) => sum + (s.discrepancyAmount ?? 0), 0)
  const totalOver  = filtered.filter(s => s.discrepancyType === 'OVER').reduce((sum, s) => sum + (s.discrepancyAmount ?? 0), 0)
  const resolved   = filtered.filter(s => s.discrepancyResolution).length
  const cashRecovered = filtered.filter(s => s.discrepancyResolution === 'CASH_RECOVERY').reduce((sum, s) => sum + (s.cashRecoveryAmount ?? 0), 0)

  if (isLoading) return <div className="px-5 py-4"><SkeletonRows count={4} /></div>
  if (error) return <p role="alert" className="ui-alert ui-alert-error m-4 text-sm">{parseApiError(error, 'Failed to load discrepancies')}</p>
  if (filtered.length === 0 && !hasFilter) {
    return <div className="ui-empty p-6">No discrepancies recorded yet.</div>
  }

  return (
    <div>
      {/* Summary bar */}
      <div className="flex flex-wrap gap-4 px-6 py-3 border-t border-slate-100 bg-red-50/40">
        <div className="text-xs">
          <span className="text-slate-500">SHORT total: </span>
          <span className="font-semibold text-red-700">{fmtAmt(totalShort)}</span>
        </div>
        <div className="text-xs">
          <span className="text-slate-500">OVER total: </span>
          <span className="font-semibold text-amber-700">{fmtAmt(totalOver)}</span>
        </div>
        <div className="text-xs">
          <span className="text-slate-500">Resolved: </span>
          <span className="font-semibold text-slate-700">{resolved} / {filtered.length}</span>
        </div>
        {cashRecovered > 0 && (
          <div className="text-xs">
            <span className="text-slate-500">Cash recovered: </span>
            <span className="font-semibold text-green-700">{fmtAmt(cashRecovered)}</span>
          </div>
        )}
      </div>

      {/* Filter bar */}
      <div className="flex flex-wrap items-center gap-3 px-6 py-3 border-t border-slate-100 bg-slate-50/60">
        <span className="text-xs font-medium text-slate-500">Filter:</span>
        <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
          <input aria-label="From date"
            type="date"
            value={fromDate}
            onChange={(e) => { setFromDate(e.target.value); setPage(0) }}
            className="flex-1 min-w-[8.5rem] border border-slate-300 rounded-lg px-2.5 py-1.5 text-xs"
          />
          <span className="text-slate-500 text-xs">to</span>
          <input aria-label="To date"
            type="date"
            value={toDate}
            onChange={(e) => { setToDate(e.target.value); setPage(0) }}
            className="flex-1 min-w-[8.5rem] border border-slate-300 rounded-lg px-2.5 py-1.5 text-xs"
          />
        </div>
        <div className="flex items-center gap-1">
          {(['ALL', 'SHORT', 'OVER'] as const).map(t => (
            <button
              key={t}
              onClick={() => { setTypeFilter(t); setPage(0) }}
              className={`px-2.5 py-1 text-xs rounded-full border transition-colors ${
                typeFilter === t
                  ? t === 'SHORT' ? 'bg-red-600 border-red-600 text-white'
                    : t === 'OVER' ? 'bg-amber-500 border-amber-500 text-white'
                    : 'bg-blue-600 border-blue-600 text-white'
                  : 'border-slate-200 text-slate-600 hover:bg-slate-100'
              }`}
            >
              {t}
            </button>
          ))}
        </div>
        {(fromDate || toDate || typeFilter !== 'ALL') && (
          <button onClick={clearFilter} className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-slate-500 hover:text-slate-700 underline">
            Clear
          </button>
        )}
        <span className="ml-auto text-xs text-slate-500">{filtered.length} shift{filtered.length !== 1 ? 's' : ''}</span>
      </div>

      {/* Cards below md */}
      <ul className="md:hidden divide-y divide-border border-t border-border" aria-label="Discrepancies">
        {paged.length === 0 ? (
          <li className="ui-empty px-4 py-6">No discrepancies match the selected filters.</li>
        ) : (
          paged.map((shift) => {
            const note = shift.discrepancyResolutionNote ?? shift.investigationNote ?? shift.discrepancyReason
            return (
              <li key={shift.id} data-testid="discrepancy-card" className="space-y-2 bg-surface px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm font-semibold text-ink">{shift.operatorName}</p>
                    <p className="text-xs text-ink-3">{shift.shiftDate} · {shift.shiftWindow}</p>
                  </div>
                  <DiscrepancyBadge type={shift.discrepancyType} amount={shift.discrepancyAmount ?? 0} />
                </div>
                <div><DiscrepancyStatus shift={shift} /></div>
                {shift.cashRecoveryAmount != null && shift.cashRecoveryAmount > 0 && (
                  <p className="text-xs text-ok">Recovered: {fmtAmt(shift.cashRecoveryAmount)}</p>
                )}
                {note && <p className="text-xs text-ink-2">{note}</p>}
                <DiscrepancyAction shift={shift} role={role} onOpen={setResolvingShift} className="w-full" />
              </li>
            )
          })
        )}
      </ul>

      {/* Table from md up */}
      <ScrollRegion label="Discrepancies" className="hidden md:block overflow-x-auto border-t border-slate-100">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-xs text-slate-500">
              <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">ID</th>
              <th scope="col" className="text-left px-5 py-3 font-medium">Date</th>
              <th scope="col" className="text-left px-5 py-3 font-medium">Operator</th>
              <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">DU</th>
              <th scope="col" className="text-left px-5 py-3 font-medium">Type</th>
              <th scope="col" className="text-left px-5 py-3 font-medium">Amount</th>
              <th scope="col" className="text-left px-5 py-3 font-medium">Status</th>
              <th scope="col" className="text-left px-5 py-3 font-medium hidden md:table-cell">Note</th>
              <th scope="col" className="text-right px-5 py-3 font-medium"><span className="sr-only">Action</span></th>
            </tr>
          </thead>
          <tbody>
            {paged.length === 0 ? (
              <tr>
                <td colSpan={9} className="ui-empty px-6 py-6">No discrepancies match the selected filters.</td>
              </tr>
            ) : (
              paged.map((shift) => (
                <tr key={shift.id} className="border-b border-slate-100 hover:bg-slate-50/60 transition-colors">
                  <td className="px-5 py-3 text-slate-500 text-xs font-mono hidden md:table-cell">{shift.id}</td>
                  <td className="px-5 py-3 text-xs text-slate-600">{shift.shiftDate}</td>
                  <td className="px-5 py-3 text-sm text-slate-700 font-medium">{shift.operatorName}</td>
                  <td className="px-5 py-3 text-xs text-slate-500 hidden md:table-cell">
                    {shift.duName ?? `DU #${shift.duNumber}`}
                  </td>
                  <td className="px-5 py-3">
                    <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${
                      shift.discrepancyType === 'SHORT' ? 'bg-red-100 text-red-700' : 'bg-amber-100 text-amber-700'
                    }`}>
                      {shift.discrepancyType}
                    </span>
                  </td>
                  <td className="px-5 py-3 text-sm font-semibold">
                    <span className={shift.discrepancyType === 'SHORT' ? 'text-red-700' : 'text-amber-700'}>
                      {fmtAmt(shift.discrepancyAmount)}
                    </span>
                    {shift.cashRecoveryAmount != null && shift.cashRecoveryAmount > 0 && (
                      <span className="block text-xs text-green-600 font-normal mt-0.5">
                        Recovered: {fmtAmt(shift.cashRecoveryAmount)}
                      </span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    <DiscrepancyStatus shift={shift} />
                  </td>
                  <td className="px-5 py-3 text-xs text-slate-500 hidden md:table-cell max-w-xs truncate">
                    {shift.discrepancyResolutionNote ?? shift.investigationNote ?? shift.discrepancyReason ?? '—'}
                  </td>
                  <td className="px-5 py-3 text-right">
                    <DiscrepancyAction shift={shift} role={role} onOpen={setResolvingShift}
                      className="min-h-0 px-3 py-1.5 text-xs" />
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </ScrollRegion>

      {resolvingShift && (
        <ResolveDiscrepancyModal shift={resolvingShift} onClose={() => setResolvingShift(null)} />
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between px-6 py-3 border-t border-slate-100 bg-slate-50/60">
          <span className="text-xs text-slate-500">
            Page {safePage + 1} of {totalPages}
            <span className="ml-2 text-slate-500">
              (showing {safePage * PAGE_SIZE + 1}–{Math.min((safePage + 1) * PAGE_SIZE, filtered.length)} of {filtered.length})
            </span>
          </span>
          <div className="flex gap-1">
            <button aria-label="First page" onClick={() => setPage(0)} disabled={safePage === 0} className="ui-btn ui-btn-secondary min-h-0 px-2.5 py-1 text-xs disabled:opacity-30">«</button>
            <button onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={safePage === 0} className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs disabled:opacity-30">Prev</button>
            {Array.from({ length: totalPages }, (_, i) => i)
              .filter((i) => Math.abs(i - safePage) <= 2)
              .map((i) => (
                <button
                  key={i}
                  onClick={() => setPage(i)}
                  className={`ui-btn min-h-0 px-3 py-1 text-xs rounded-md border transition-colors ${
                    i === safePage ? 'bg-blue-600 border-blue-600 text-white' : 'border-slate-200 hover:bg-slate-100 text-slate-600'
                  }`}
                >
                  {i + 1}
                </button>
              ))}
            <button onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))} disabled={safePage === totalPages - 1} className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs disabled:opacity-30">Next</button>
            <button aria-label="Last page" onClick={() => setPage(totalPages - 1)} disabled={safePage === totalPages - 1} className="ui-btn ui-btn-secondary min-h-0 px-2.5 py-1 text-xs disabled:opacity-30">»</button>
          </div>
        </div>
      )}
    </div>
  )
}
