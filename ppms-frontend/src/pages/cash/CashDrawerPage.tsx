import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { usePumpStore } from '../../store/usePumpStore'
import { cashApi } from '../../api/cashApi'
import type { CashEventType, RecordCashEventRequest } from '../../api/cashApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'

const EVENT_TYPES: { value: CashEventType; label: string }[] = [
  { value: 'OPENING_BALANCE', label: 'Opening Balance' },
  { value: 'CASH_IN',         label: 'Cash In' },
  { value: 'CASH_OUT',        label: 'Cash Out' },
  { value: 'CLOSING_BALANCE', label: 'Closing Balance' },
]

const EVENT_STYLES: Record<CashEventType, string> = {
  OPENING_BALANCE: 'bg-blue-100 text-blue-700',
  CASH_IN:         'bg-emerald-100 text-emerald-700',
  CASH_OUT:        'bg-red-100 text-red-700',
  CLOSING_BALANCE: 'bg-slate-100 text-slate-700',
}

function fmtAmt(n: number) {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function fmtDate(d: string) {
  return new Date(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

export default function CashDrawerPage() {
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()

  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const [eventTypeFilter, setEventTypeFilter] = useState<CashEventType | ''>('')

  const { data: cashData, isLoading } = useQuery({
    queryKey:  ['cashEvents', pumpId, page, pageSize, eventTypeFilter],
    queryFn:   () => cashApi.getCashEvents(pumpId!, page, pageSize, eventTypeFilter || undefined),
    enabled:   !!pumpId,
  })

  const [form, setForm] = useState<RecordCashEventRequest>({
    eventType: 'CASH_IN',
    amount: 0,
    description: '',
    eventDate: new Date().toISOString().slice(0, 10),
  })
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: RecordCashEventRequest) => cashApi.recordCashEvent(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      setForm({ eventType: 'CASH_IN', amount: 0, description: '', eventDate: new Date().toISOString().slice(0, 10) })
      setFormError(null)
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to record event'),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.description.trim()) { setFormError('Description is required'); return }
    if (form.amount < 0) { setFormError('Amount cannot be negative'); return }
    createMutation.mutate(form)
  }

  const balance = cashData?.currentBalance ?? 0

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Header ── */}
      <div>
        <h1 className="text-xl font-bold text-slate-800">Cash Drawer</h1>
        <p className="text-sm text-slate-500 mt-0.5">Track cash movements in and out of the pump safe</p>
      </div>

      {/* ── Balance card ── */}
      <div className="bg-gradient-to-r from-emerald-600 to-emerald-700 rounded-2xl px-6 py-5 text-white">
        <p className="text-emerald-200 text-sm">Current Balance</p>
        <p className="text-3xl font-bold mt-1">{fmtAmt(balance)}</p>
        <p className="text-emerald-200 text-xs mt-1">Calculated from all cash-in minus cash-out events</p>
      </div>

      {/* ── Add event form ── */}
      <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 px-5 py-4 space-y-3">
        <p className="text-sm font-semibold text-slate-700">Record Cash Movement</p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className="text-xs text-slate-500 block mb-1">Event Type</label>
            <SearchableSelect
              value={form.eventType}
              onChange={v => setForm(f => ({ ...f, eventType: v as CashEventType }))}
              options={EVENT_TYPES.map(t => ({ value: t.value, label: t.label }))}
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Amount (₹)</label>
            <input
              type="number"
              min="0"
              step="0.01"
              value={form.amount || ''}
              onChange={e => setForm(f => ({ ...f, amount: parseFloat(e.target.value) || 0 }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="0.00"
              required
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Description</label>
            <input
              type="text"
              value={form.description}
              onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="e.g. Shift 1 cash deposited"
              required
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Date</label>
            <input
              type="date"
              value={form.eventDate}
              onChange={e => setForm(f => ({ ...f, eventDate: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              required
            />
          </div>
        </div>

        {formError && <p className="text-xs text-red-600">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {createMutation.isPending ? 'Saving…' : 'Record Event'}
        </button>
      </form>

      {/* ── Event list ── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-3 border-b border-slate-100 flex flex-wrap items-center gap-3">
          <p className="text-sm font-semibold text-slate-700 mr-auto">Cash Events</p>

          <div className="w-44">
            <SearchableSelect
              value={eventTypeFilter}
              onChange={v => { setEventTypeFilter(v as any); setPage(0) }}
              options={[
                { value: '', label: 'All Types' },
                ...EVENT_TYPES.map(t => ({ value: t.value, label: t.label })),
              ]}
              size="sm"
            />
          </div>
        </div>

        {isLoading ? (
          <p className="text-sm text-slate-400 px-5 py-6">Loading…</p>
        ) : (cashData?.events?.content ?? []).length === 0 ? (
          <p className="text-sm text-slate-400 px-5 py-6">No cash events recorded yet.</p>
        ) : (
          <>
            <div className="divide-y divide-slate-100">
              {(cashData?.events?.content ?? []).map(ev => (
                <div key={ev.id} className="px-5 py-3 flex items-center justify-between gap-3">
                  <div className="flex items-center gap-3 min-w-0">
                    <span className={`text-xs font-semibold px-2 py-0.5 rounded-full flex-shrink-0 ${EVENT_STYLES[ev.eventType]}`}>
                      {ev.eventType.replace('_', ' ')}
                    </span>
                    <div className="min-w-0">
                      <p className="text-sm text-slate-700 truncate">{ev.description}</p>
                      <p className="text-xs text-slate-400">{fmtDate(ev.eventDate)}</p>
                    </div>
                  </div>
                  <span className={`text-sm font-semibold flex-shrink-0 ${
                    ev.eventType === 'CASH_OUT' ? 'text-red-600' : 'text-emerald-600'
                  }`}>
                    {ev.eventType === 'CASH_OUT' ? '−' : '+'}{fmtAmt(ev.amount)}
                  </span>
                </div>
              ))}
            </div>
            {cashData?.events && (
              <div className="px-5">
                <Pagination
                  data={cashData.events}
                  onPageChange={p => setPage(p)}
                  onPageSizeChange={s => { setPageSize(s); setPage(0) }}
                  pageSizeOptions={[10, 20, 50]}
                />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
