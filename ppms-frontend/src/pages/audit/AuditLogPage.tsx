import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { auditApi } from '../../api/auditApi'
import { usePumpStore } from '../../store/usePumpStore'
import type { AuditAction } from '../../api/auditApi'

const ACTION_STYLES: Partial<Record<AuditAction, string>> = {
  // User management
  USER_CREATED:               'bg-blue-100 text-blue-700',
  USER_DEACTIVATED:           'bg-red-100 text-red-700',
  USER_STATUS_CHANGED:        'bg-amber-100 text-amber-700',
  // Authentication
  LOGIN:                      'bg-slate-100 text-slate-600',
  LOGIN_FAILED:               'bg-red-100 text-red-700',
  TOKEN_REVOKED:              'bg-slate-100 text-slate-600',
  // Fuel pricing
  FUEL_PRICE_UPDATED:         'bg-purple-100 text-purple-700',
  // Shift lifecycle
  SHIFT_OPENED:               'bg-sky-100 text-sky-700',
  SHIFT_CLOSED:               'bg-emerald-100 text-emerald-700',
  DISCREPANCY_RESOLVED:       'bg-amber-100 text-amber-700',
  // Credit
  CREDIT_ENTRY_VOIDED:        'bg-red-100 text-red-700',
  CREDIT_LIMIT_CHANGED:       'bg-orange-100 text-orange-700',
  CREDIT_PAYMENT_RECEIVED:    'bg-teal-100 text-teal-700',
  INTEREST_APPLIED:           'bg-orange-100 text-orange-700',
  CREDIT_CLIENT_CREATED:      'bg-blue-100 text-blue-700',
  CREDIT_CLIENT_DELETED:      'bg-red-100 text-red-700',
  CREDIT_ENTRY_REASSIGNED:    'bg-amber-100 text-amber-700',
  // Inventory
  DELIVERY_RECORDED:          'bg-indigo-100 text-indigo-700',
  DIP_CHECK_RECORDED:         'bg-indigo-100 text-indigo-700',
  // Documents / Expenses
  DOCUMENT_ADDED:             'bg-slate-100 text-slate-700',
  EXPENSE_RECORDED:           'bg-rose-100 text-rose-700',
  // Payroll
  PAYROLL_APPROVED:           'bg-emerald-100 text-emerald-700',
  // Pump management
  PUMP_CREATED:               'bg-blue-100 text-blue-700',
  PUMP_DELETED:               'bg-red-100 text-red-700',
  NOZZLE_ADDED:               'bg-blue-100 text-blue-700',
  TANK_ADDED:                 'bg-blue-100 text-blue-700',
  // Ancillary
  ANCILLARY_SALE_RECORDED:    'bg-teal-100 text-teal-700',
  ANCILLARY_DELIVERY_RECORDED:'bg-indigo-100 text-indigo-700',
  // Operations
  HANDOVER_COMPLETED:         'bg-emerald-100 text-emerald-700',
  PUMP_CLOSURE_ADDED:         'bg-slate-100 text-slate-700',
  BANK_STATEMENT_IMPORTED:    'bg-indigo-100 text-indigo-700',
  SUPPLIER_PAYMENT_RECORDED:  'bg-teal-100 text-teal-700',
  PUMP_CONFIG_UPDATED:        'bg-purple-100 text-purple-700',
}

const PAGE_SIZE_OPTIONS = [10, 25, 50]

function toIsoDate(d: Date): string {
  return d.toISOString().slice(0, 10)
}

function fmtDateTime(d: string) {
  return new Date(d).toLocaleString('en-IN', {
    day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  })
}

const today     = toIsoDate(new Date())
const yesterday = toIsoDate(new Date(Date.now() - 86_400_000))

export default function AuditLogPage() {
  const [from, setFrom]       = useState(yesterday)
  const [to, setTo]           = useState(today)
  const [page, setPage]       = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { selectedPumpId: pumpId } = usePumpStore()

  const { data, isLoading } = useQuery({
    queryKey:  ['auditLogs', pumpId, page, pageSize, from, to],
    queryFn:   () => auditApi.getAuditLogs({
      pumpId: pumpId!,
      page,
      size: pageSize,
      from: from || undefined,
      to:   to   || undefined,
    }),
    enabled: !!pumpId,
  })

  const logs         = data?.content            ?? []
  const totalPages   = data?.page?.totalPages   ?? 0
  const totalEvents  = data?.page?.totalElements ?? 0
  const currentPage  = data?.page?.number       ?? page

  function handleFromChange(value: string) {
    setFrom(value)
    setPage(0)
  }

  function handleToChange(value: string) {
    setTo(value)
    setPage(0)
  }

  function handlePageSizeChange(value: number) {
    setPageSize(value)
    setPage(0)
  }

  function clearFilters() {
    setFrom('')
    setTo('')
    setPage(0)
  }

  const rangeStart = totalEvents === 0 ? 0 : currentPage * pageSize + 1
  const rangeEnd   = Math.min((currentPage + 1) * pageSize, totalEvents)

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Header ── */}
      <div>
        <h1 className="text-xl font-bold text-slate-800">Audit Log</h1>
        <p className="text-sm text-slate-500 mt-0.5">
          Full history of all sensitive operations — who did what and when
        </p>
      </div>

      {/* ── Filters row ── */}
      <div className="flex flex-wrap items-end gap-3">
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-slate-600">From</label>
          <input
            type="date"
            value={from}
            onChange={e => handleFromChange(e.target.value)}
            max={to || undefined}
            className="text-sm border border-slate-200 rounded-lg px-3 py-1.5 text-slate-700 focus:outline-none focus:ring-2 focus:ring-slate-300"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-slate-600">To</label>
          <input
            type="date"
            value={to}
            onChange={e => handleToChange(e.target.value)}
            min={from || undefined}
            className="text-sm border border-slate-200 rounded-lg px-3 py-1.5 text-slate-700 focus:outline-none focus:ring-2 focus:ring-slate-300"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-xs font-medium text-slate-600">Per page</label>
          <select
            value={pageSize}
            onChange={e => handlePageSizeChange(Number(e.target.value))}
            className="text-sm border border-slate-200 rounded-lg px-3 py-1.5 text-slate-700 focus:outline-none focus:ring-2 focus:ring-slate-300 bg-white"
          >
            {PAGE_SIZE_OPTIONS.map(n => (
              <option key={n} value={n}>{n} / page</option>
            ))}
          </select>
        </div>
        {(from || to) && (
          <button
            onClick={clearFilters}
            className="text-xs text-slate-500 hover:text-slate-700 underline pb-1.5"
          >
            Clear filter
          </button>
        )}
      </div>

      {/* ── Log list ── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-3 border-b border-slate-100 flex items-center justify-between">
          <p className="text-sm font-semibold text-slate-700">
            {from || to ? 'Filtered Events' : 'All Events'} ({isLoading ? '…' : totalEvents})
          </p>
          {!isLoading && totalPages > 0 && (
            <p className="text-xs text-slate-400">
              Page {currentPage + 1} of {totalPages}
            </p>
          )}
        </div>

        {isLoading ? (
          <p className="text-sm text-slate-400 px-5 py-6">Loading…</p>
        ) : logs.length === 0 ? (
          <p className="text-sm text-slate-400 px-5 py-6">
            No audit events found{from || to ? ' for the selected date range' : ''}.
          </p>
        ) : (
          <div className="divide-y divide-slate-100">
            {logs.map(log => (
              <div key={log.id} className="px-5 py-3">
                <div className="flex items-center justify-between gap-2 mb-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${ACTION_STYLES[log.action] ?? 'bg-slate-100 text-slate-600'}`}>
                      {log.action.replace(/_/g, ' ')}
                    </span>
                    <span className="text-xs text-slate-500">by <span className="font-medium text-slate-700">{log.actorName}</span></span>
                  </div>
                  <p className="text-xs text-slate-400 flex-shrink-0">{fmtDateTime(log.createdAt)}</p>
                </div>
                <p className="text-sm text-slate-700">{log.description}</p>
                {log.entityId && (
                  <p className="text-xs text-slate-400 mt-0.5">{log.entityType} #{log.entityId}</p>
                )}
              </div>
            ))}
          </div>
        )}

        {/* ── Pagination controls — always shown when there's data ── */}
        {!isLoading && totalEvents > 0 && (
          <div className="px-5 py-3 border-t border-slate-100 flex items-center justify-between gap-4">
            <button
              onClick={() => setPage(p => p - 1)}
              disabled={currentPage === 0}
              className="text-sm px-3 py-1.5 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              ← Previous
            </button>

            {/* Page number pills */}
            <div className="flex items-center gap-1">
              {Array.from({ length: totalPages }, (_, i) => i).map(i => (
                <button
                  key={i}
                  onClick={() => setPage(i)}
                  className={`w-7 h-7 text-xs rounded-md border ${
                    i === currentPage
                      ? 'bg-slate-700 text-white border-slate-700'
                      : 'border-slate-200 text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  {i + 1}
                </button>
              ))}
            </div>

            <button
              onClick={() => setPage(p => p + 1)}
              disabled={currentPage >= totalPages - 1}
              className="text-sm px-3 py-1.5 rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              Next →
            </button>
          </div>
        )}

        {/* ── Record count footer ── */}
        {!isLoading && totalEvents > 0 && (
          <div className="px-5 pb-3 text-center">
            <span className="text-xs text-slate-400">
              Showing {rangeStart}–{rangeEnd} of {totalEvents} events
            </span>
          </div>
        )}
      </div>
    </div>
  )
}
