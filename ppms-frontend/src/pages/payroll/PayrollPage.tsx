import { useState, useMemo } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { usePumpStore } from '../../store/usePumpStore'
import { userApi } from '../../api/userApi'
import { payrollApi } from '../../api/payrollApi'
import type { GeneratePayrollRequest, PayrollRecord, PayrollStatus } from '../../api/payrollApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'

const STATUS_STYLES: Record<PayrollStatus, string> = {
  DRAFT:    'bg-amber-100 text-amber-700',
  APPROVED: 'bg-blue-100 text-blue-700',
  PAID:     'bg-emerald-100 text-emerald-700',
}

// Order in which status groups are displayed
const STATUS_ORDER: PayrollStatus[] = ['DRAFT', 'APPROVED', 'PAID']

const NEXT_STATUS: Partial<Record<PayrollStatus, PayrollStatus>> = {
  DRAFT:    'APPROVED',
  APPROVED: 'PAID',
}

function fmtAmt(n: number) {
  return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function fmtDate(d: string) {
  return new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** Returns "2026-04" from a date string */
function toMonthKey(dateStr: string): string {
  return dateStr.slice(0, 7)
}

/** Returns "Apr 2026" from "2026-04" */
function fmtMonthKey(key: string): string {
  const [year, month] = key.split('-')
  return new Date(Number(year), Number(month) - 1, 1)
    .toLocaleDateString('en-IN', { month: 'short', year: 'numeric' })
}

function todayIso()     { return new Date().toISOString().slice(0, 10) }
function yesterdayIso() { return new Date(Date.now() - 86_400_000).toISOString().slice(0, 10) }

// ── Collapsible group header ──────────────────────────────────────────────────
interface GroupProps {
  status: PayrollStatus
  /** Current page's records (already sliced). */
  records: PayrollRecord[]
  /** Total count for this status across all pages — shown in header. */
  totalRecords: number
  /** Gross total across ALL records for this status — shown in header. */
  totalAmount: number
  page: number
  pageSize: number
  onPageChange: (p: number) => void
  onPageSizeChange: (s: number) => void
  isOpen: boolean
  onToggle: () => void
  staff: any[]
  onStatusChange: (recordId: number, status: PayrollStatus) => void
  isPending: boolean
}

function PayrollGroup({
  status, records, totalRecords, totalAmount,
  page, pageSize, onPageChange, onPageSizeChange,
  isOpen, onToggle, staff, onStatusChange, isPending,
}: GroupProps) {
  const next = NEXT_STATUS[status]

  const totalPages   = Math.max(1, Math.ceil(totalRecords / pageSize))
  const pagedData = {
    content:       records,
    page,
    pageSize,
    totalElements: totalRecords,
    totalPages,
    hasNext:       page < totalPages - 1,
    hasPrevious:   page > 0,
  }

  return (
    <div className="border border-slate-200 rounded-xl overflow-hidden">
      {/* Group header — click to toggle */}
      <button
        type="button"
        onClick={onToggle}
        className="w-full flex items-center justify-between px-5 py-3 bg-white hover:bg-slate-50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <span className={`text-xs font-semibold px-2.5 py-0.5 rounded-full ${STATUS_STYLES[status]}`}>
            {status}
          </span>
          <span className="text-sm text-slate-500">
            {totalRecords} record{totalRecords !== 1 ? 's' : ''}
          </span>
          <span className="text-sm font-semibold text-slate-700">{fmtAmt(totalAmount)}</span>
        </div>
        <span className="text-slate-400 text-sm">{isOpen ? '▲' : '▼'}</span>
      </button>

      {/* Records — shown only when expanded */}
      {isOpen && (
        <div className="border-t border-slate-100">
        <div className="divide-y divide-slate-100">
          {records.map(r => {
            const staffMember = staff.find((s: any) => s.id === r.userId)
            return (
              <div key={r.id} className="px-5 py-4 space-y-2.5 bg-white">
                {/* Header row */}
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-slate-700">
                      {staffMember?.fullName ?? `Staff #${r.userId}`}
                    </span>
                    <span className="text-xs text-slate-400">
                      {fmtDate(r.periodFrom)} – {fmtDate(r.periodTo)}
                    </span>
                  </div>
                  <span className="text-base font-bold text-slate-800 flex-shrink-0">{fmtAmt(r.grossAmount)}</span>
                </div>

                {/* Breakdown */}
                <div className="bg-slate-50 rounded-lg border border-slate-100 divide-y divide-slate-100 text-xs">
                  {r.salaryType === 'DAILY' ? (
                    <>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-blue-400 shrink-0" />
                          <span className="text-slate-500">Total days in period</span>
                        </div>
                        <span className="font-medium text-slate-700">{r.totalDays ?? 0}</span>
                      </div>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-amber-400 shrink-0" />
                          <span className="text-slate-500">Leave days deducted</span>
                        </div>
                        <span className="font-medium text-amber-700">− {r.leaveDays ?? 0}</span>
                      </div>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-emerald-400 shrink-0" />
                          <span className="text-slate-500">Days worked</span>
                          {r.dailyRateSnapshot != null && (
                            <span className="text-slate-400">@ {fmtAmt(r.dailyRateSnapshot)}/day</span>
                          )}
                        </div>
                        <span className="font-medium text-slate-700">{r.daysWorked ?? 0} days</span>
                      </div>
                    </>
                  ) : (
                    <>
                      {r.shift1Shifts > 0 && (
                        <div className="flex items-center justify-between px-3 py-2">
                          <div className="flex items-center gap-2">
                            <span className="w-2 h-2 rounded-full bg-indigo-400 shrink-0" />
                            <span className="text-slate-500">Night (12AM–8AM)</span>
                            <span className="text-slate-400">{r.shift1Shifts} shift{r.shift1Shifts !== 1 ? 's' : ''} · {r.shift1Hours.toFixed(2)} hrs</span>
                            {r.shift1RateSnapshot != null && (
                              <span className="text-slate-400">@ {fmtAmt(r.shift1RateSnapshot)}/hr</span>
                            )}
                          </div>
                          <span className="font-medium text-slate-700">
                            {r.shift1RateSnapshot != null ? fmtAmt(r.shift1Hours * r.shift1RateSnapshot) : '—'}
                          </span>
                        </div>
                      )}
                      {r.standardShifts > 0 && (
                        <div className="flex items-center justify-between px-3 py-2">
                          <div className="flex items-center gap-2">
                            <span className="w-2 h-2 rounded-full bg-amber-400 shrink-0" />
                            <span className="text-slate-500">Day (8AM–12AM)</span>
                            <span className="text-slate-400">{r.standardShifts} shift{r.standardShifts !== 1 ? 's' : ''} · {r.standardHours.toFixed(2)} hrs</span>
                            {r.standardRateSnapshot != null && (
                              <span className="text-slate-400">@ {fmtAmt(r.standardRateSnapshot)}/hr</span>
                            )}
                          </div>
                          <span className="font-medium text-slate-700">
                            {r.standardRateSnapshot != null ? fmtAmt(r.standardHours * r.standardRateSnapshot) : '—'}
                          </span>
                        </div>
                      )}
                      {r.shift1Shifts === 0 && r.standardShifts === 0 && (
                        <p className="px-3 py-2 text-slate-400">No closed shifts in this period.</p>
                      )}
                    </>
                  )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between gap-2">
                  <p className="text-xs text-slate-400">
                    {r.salaryType === 'DAILY'
                      ? `${r.daysWorked ?? 0} day${r.daysWorked !== 1 ? 's' : ''} worked`
                      : `${r.totalShifts} shift${r.totalShifts !== 1 ? 's' : ''} total`}
                    {r.notes && ` · ${r.notes}`}
                  </p>
                  {next && (
                    <button
                      onClick={() => onStatusChange(r.id, next)}
                      disabled={isPending}
                      className="text-xs bg-blue-600 text-white px-3 py-1 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
                    >
                      Mark {next}
                    </button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
        <Pagination
          data={pagedData}
          onPageChange={onPageChange}
          onPageSizeChange={onPageSizeChange}
          pageSizeOptions={[10, 20, 50]}
        />
        </div>
      )}
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────
export default function PayrollPage() {
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()

  const { data: staff = [] } = useQuery({
    queryKey: ['staff', pumpId],
    queryFn:  () => userApi.getStaff(pumpId!),
    enabled:  !!pumpId,
  })

  const { data: records = [], isLoading } = useQuery({
    queryKey: ['payroll', pumpId],
    queryFn:  () => payrollApi.getPayroll(pumpId!),
    enabled:  !!pumpId,
  })

  const [form, setForm] = useState<GeneratePayrollRequest>(() => ({
    userId: 0,
    periodFrom: yesterdayIso(),
    periodTo: todayIso(),
    notes: null,
  }))
  const [formError, setFormError] = useState<string | null>(null)

  // ── Filters ────────────────────────────────────────────────────────────────
  // Derive sorted list of unique month keys ("2026-04") from all records
  const monthOptions = useMemo(() => {
    const keys = new Set(records.map(r => toMonthKey(r.periodFrom)))
    return Array.from(keys).sort().reverse()   // newest first
  }, [records])

  const [selectedMonth, setSelectedMonth] = useState<string>('')

  // ── Per-status pagination (independent, default page size = 10) ──────────
  const [draftPage,        setDraftPage]        = useState(0)
  const [draftPageSize,    setDraftPageSize]    = useState(10)
  const [approvedPage,     setApprovedPage]     = useState(0)
  const [approvedPageSize, setApprovedPageSize] = useState(10)
  const [paidPage,         setPaidPage]         = useState(0)
  const [paidPageSize,     setPaidPageSize]     = useState(10)

  // ── Accordion: track which status group is open ────────────────────────────
  const [openGroup, setOpenGroup] = useState<PayrollStatus | null>('DRAFT')

  function toggleGroup(status: PayrollStatus) {
    setOpenGroup(prev => (prev === status ? null : status))
  }

  // ── Filtered + grouped records ─────────────────────────────────────────────
  const filtered = useMemo(() => {
    if (!selectedMonth) return records
    return records.filter(r => toMonthKey(r.periodFrom) === selectedMonth)
  }, [records, selectedMonth])

  const grouped = useMemo(() => {
    const map: Record<PayrollStatus, PayrollRecord[]> = { DRAFT: [], APPROVED: [], PAID: [] }
    filtered.forEach(r => map[r.status].push(r))
    return map
  }, [filtered])

  // ── Mutations ──────────────────────────────────────────────────────────────
  const generateMutation = useMutation({
    mutationFn: (data: GeneratePayrollRequest) => payrollApi.generatePayroll(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payroll', pumpId] })
      setForm({ userId: 0, periodFrom: yesterdayIso(), periodTo: todayIso(), notes: null })
      setFormError(null)
      setOpenGroup('DRAFT')
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to generate payroll'),
  })

  const statusMutation = useMutation({
    mutationFn: ({ recordId, status }: { recordId: number; status: PayrollStatus }) =>
      payrollApi.updateStatus(pumpId!, recordId, status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['payroll', pumpId] }),
    onError: (err: any) => alert(err?.response?.data?.message ?? 'Failed to update status'),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.userId) { setFormError('Please select a staff member'); return }
    if (!form.periodFrom || !form.periodTo) { setFormError('Period dates are required'); return }
    generateMutation.mutate(form)
  }

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Header ── */}
      <div>
        <h1 className="text-xl font-bold text-slate-800">Payroll</h1>
        <p className="text-sm text-slate-500 mt-0.5">Calculate and track staff salary payments based on shift history</p>
      </div>

      {/* ── Generate form ── */}
      <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 px-5 py-4 space-y-3">
        <p className="text-sm font-semibold text-slate-700">Generate Payroll</p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className="text-xs text-slate-500 block mb-1">Staff Member</label>
            <SearchableSelect
              value={form.userId ? form.userId.toString() : ''}
              onChange={v => setForm(f => ({ ...f, userId: parseInt(v) || 0 }))}
              placeholder="Select staff…"
              options={staff.map((s: any) => ({ value: s.id.toString(), label: `${s.fullName} — ${s.role}` }))}
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Period From</label>
            <input
              type="date"
              value={form.periodFrom}
              onChange={e => setForm(f => ({ ...f, periodFrom: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              required
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Period To</label>
            <input
              type="date"
              value={form.periodTo}
              onChange={e => setForm(f => ({ ...f, periodTo: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              required
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Notes (optional)</label>
            <input
              type="text"
              value={form.notes ?? ''}
              onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="Any notes about this pay period"
            />
          </div>
        </div>

        <p className="text-xs text-slate-400">
          Operators: actual hours per closed shift × hourly rate (night/day separately).
          Managers &amp; Admins: daily rate × (days in period − leave days).
          Configure rates in Setup → Staff → ₹ Rates.
        </p>

        {formError && <p className="text-xs text-red-600">{formError}</p>}

        <button
          type="submit"
          disabled={generateMutation.isPending}
          className="bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {generateMutation.isPending ? 'Calculating…' : 'Generate Payroll'}
        </button>
      </form>

      {/* ── Payroll records ── */}
      <div className="space-y-3">
        {/* Section header + month filter */}
        <div className="flex items-center justify-between gap-3">
          <p className="text-sm font-semibold text-slate-700">
            Payroll Records
            {filtered.length > 0 && (
              <span className="ml-1.5 text-slate-400 font-normal">({filtered.length})</span>
            )}
          </p>

          {monthOptions.length > 0 && (
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500">Month</label>
              <select
                value={selectedMonth}
                onChange={e => {
                  setSelectedMonth(e.target.value)
                  setDraftPage(0); setApprovedPage(0); setPaidPage(0)
                }}
                className="text-sm border border-slate-200 rounded-lg px-3 py-1.5 text-slate-700 focus:outline-none focus:ring-2 focus:ring-slate-300 bg-white"
              >
                <option value="">All months</option>
                {monthOptions.map(key => (
                  <option key={key} value={key}>{fmtMonthKey(key)}</option>
                ))}
              </select>
            </div>
          )}
        </div>

        {isLoading ? (
          <div className="bg-white rounded-xl border border-slate-200 px-5 py-6">
            <p className="text-sm text-slate-400">Loading…</p>
          </div>
        ) : filtered.length === 0 ? (
          <div className="bg-white rounded-xl border border-slate-200 px-5 py-6">
            <p className="text-sm text-slate-400">
              {records.length === 0
                ? 'No payroll records yet.'
                : 'No records for the selected month.'}
            </p>
          </div>
        ) : (
          /* One collapsible group per status — only renders groups that have records */
          <div className="space-y-2">
            {STATUS_ORDER.filter(s => grouped[s].length > 0).map(status => {
              const allForStatus = grouped[status]
              const totalRecords = allForStatus.length
              const totalAmount  = allForStatus.reduce((s, r) => s + r.grossAmount, 0)

              const pageState = {
                DRAFT:    { page: draftPage,    pageSize: draftPageSize,
                            setPage: setDraftPage,    setPageSize: setDraftPageSize },
                APPROVED: { page: approvedPage, pageSize: approvedPageSize,
                            setPage: setApprovedPage, setPageSize: setApprovedPageSize },
                PAID:     { page: paidPage,     pageSize: paidPageSize,
                            setPage: setPaidPage,     setPageSize: setPaidPageSize },
              }[status]

              const totalPages = Math.max(1, Math.ceil(totalRecords / pageState.pageSize))
              const safePage   = Math.min(pageState.page, totalPages - 1)
              const pagedRecords = allForStatus.slice(
                safePage * pageState.pageSize,
                (safePage + 1) * pageState.pageSize,
              )

              return (
                <PayrollGroup
                  key={status}
                  status={status}
                  records={pagedRecords}
                  totalRecords={totalRecords}
                  totalAmount={totalAmount}
                  page={safePage}
                  pageSize={pageState.pageSize}
                  onPageChange={pageState.setPage}
                  onPageSizeChange={s => { pageState.setPageSize(s); pageState.setPage(0) }}
                  isOpen={openGroup === status}
                  onToggle={() => toggleGroup(status)}
                  staff={staff}
                  onStatusChange={(recordId, next) => statusMutation.mutate({ recordId, status: next })}
                  isPending={statusMutation.isPending}
                />
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
