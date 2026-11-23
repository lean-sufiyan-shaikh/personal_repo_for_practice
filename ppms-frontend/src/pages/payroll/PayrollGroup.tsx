import { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import type { StaffMember } from '../../api/userApi'
import type { PayrollRecord, PayrollStatus } from '../../api/payrollApi'
import { Pagination } from '../../components/Pagination'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { NEXT_STATUS, STATUS_BADGES, fmtAmt, fmtDate } from './payrollFormat'

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
  staff: StaffMember[]
  onStatusChange: (recordId: number, status: PayrollStatus) => void
  onDelete: (recordId: number) => void
  isPending: boolean
  isDeletePending: boolean
}

export function PayrollGroup({
  status, records, totalRecords, totalAmount,
  page, pageSize, onPageChange, onPageSizeChange,
  isOpen, onToggle, staff, onStatusChange, onDelete, isPending, isDeletePending,
}: GroupProps) {
  const next = NEXT_STATUS[status]
  const [rejectRecordId, setRejectRecordId] = useState<number | null>(null)

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
    <div className="ui-card p-0 overflow-hidden">
      {/* Group header — click to toggle */}
      <button
        type="button"
        onClick={onToggle}
        className="ui-accordion-trigger"
      >
        <div className="flex items-center gap-3">
          <StatusBadge {...STATUS_BADGES[status]} />
          <span className="text-sm text-ink-3">
            {totalRecords} record{totalRecords !== 1 ? 's' : ''}
          </span>
          <MoneyText value={totalAmount} className="text-sm font-semibold text-ink" />
        </div>
        <ChevronDown size={14} strokeWidth={2} className={`ui-accordion-arrow ${isOpen ? 'ui-accordion-arrow--open' : ''}`} />
      </button>

      {/* Records — shown only when expanded */}
      {isOpen && (
        <div className="ui-accordion-content border-t border-border">
        <div className="divide-y divide-border">
          {records.map(r => {
            const staffMember = staff.find((s) => s.id === r.userId)
            return (
              <div key={r.id} className="px-5 py-4 space-y-2.5 bg-surface">
                {/* Header row */}
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-medium text-ink">
                      {staffMember?.fullName ?? `Staff #${r.userId}`}
                    </span>
                    <span className="text-xs text-ink-3">
                      {fmtDate(r.periodFrom)} – {fmtDate(r.periodTo)}
                    </span>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <MoneyText value={r.netPay} className="block text-base font-bold text-ink" />
                    {r.deductions > 0 && (
                      <MoneyText value={r.grossAmount} className="block text-xs text-ink-3 line-through" />
                    )}
                  </div>
                </div>

                {/* Breakdown */}
                <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-xs">
                  {r.salaryType === 'DAILY' ? (
                    <>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-info shrink-0" />
                          <span className="text-ink-3">Total days in period</span>
                        </div>
                        <span className="font-medium text-ink">{r.totalDays ?? 0}</span>
                      </div>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-warn shrink-0" />
                          <span className="text-ink-3">Leave days deducted</span>
                        </div>
                        <span className="font-medium text-warn">− {r.leaveDays ?? 0}</span>
                      </div>
                      <div className="flex items-center justify-between px-3 py-2">
                        <div className="flex items-center gap-2">
                          <span className="w-2 h-2 rounded-full bg-ok shrink-0" />
                          <span className="text-ink-3">Days worked</span>
                          {r.dailyRateSnapshot != null && (
                            <span className="text-ink-3">@ {fmtAmt(r.dailyRateSnapshot)}/day</span>
                          )}
                        </div>
                        <span className="font-medium text-ink">{r.daysWorked ?? 0} days</span>
                      </div>
                    </>
                  ) : (
                    <>
                      {r.shift1Shifts > 0 && (
                        <div className="flex items-center justify-between px-3 py-2">
                          <div className="flex items-center gap-2">
                            <span className="w-2 h-2 rounded-full bg-primary shrink-0" />
                            <span className="text-ink-3">Night (12AM–8AM)</span>
                            <span className="text-ink-3">{r.shift1Shifts} shift{r.shift1Shifts !== 1 ? 's' : ''} · {r.shift1Hours.toFixed(2)} hrs</span>
                            {r.shift1RateSnapshot != null && (
                              <span className="text-ink-3">@ {fmtAmt(r.shift1RateSnapshot)}/hr</span>
                            )}
                          </div>
                          <MoneyText value={r.shift1RateSnapshot != null ? r.shift1Hours * r.shift1RateSnapshot : null} className="font-medium text-ink" />
                        </div>
                      )}
                      {r.standardShifts > 0 && (
                        <div className="flex items-center justify-between px-3 py-2">
                          <div className="flex items-center gap-2">
                            <span className="w-2 h-2 rounded-full bg-accent shrink-0" />
                            <span className="text-ink-3">Day (8AM–12AM)</span>
                            <span className="text-ink-3">{r.standardShifts} shift{r.standardShifts !== 1 ? 's' : ''} · {r.standardHours.toFixed(2)} hrs</span>
                            {r.standardRateSnapshot != null && (
                              <span className="text-ink-3">@ {fmtAmt(r.standardRateSnapshot)}/hr</span>
                            )}
                          </div>
                          <MoneyText value={r.standardRateSnapshot != null ? r.standardHours * r.standardRateSnapshot : null} className="font-medium text-ink" />
                        </div>
                      )}
                      {r.shift1Shifts === 0 && r.standardShifts === 0 && (
                        <p className="px-3 py-2 text-ink-3">No closed shifts in this period.</p>
                      )}
                    </>
                  )}
                  {r.deductions > 0 && (
                    <div className="flex items-center justify-between px-3 py-2 bg-bad-bg">
                      <div className="flex items-center gap-2">
                        <span className="w-2 h-2 rounded-full bg-bad shrink-0" />
                        <span className="text-ink-3">Salary deductions</span>
                        <span className="text-xs text-ink-3">(discrepancy shortfall)</span>
                      </div>
                      <MoneyText value={-r.deductions} showSign tone="bad" className="font-medium" />
                    </div>
                  )}
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between gap-2">
                  <p className="text-xs text-ink-3">
                    {r.salaryType === 'DAILY'
                      ? `${r.daysWorked ?? 0} day${r.daysWorked !== 1 ? 's' : ''} worked`
                      : `${r.totalShifts} shift${r.totalShifts !== 1 ? 's' : ''} total`}
                    {r.notes && ` · ${r.notes}`}
                  </p>
                  <div className="flex items-center gap-2">
                    {status === 'DRAFT' && rejectRecordId === r.id ? (
                      <>
                        <button
                          onClick={() => setRejectRecordId(null)}
                          className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs"
                        >
                          Cancel
                        </button>
                        <button
                          onClick={() => onDelete(r.id)}
                          disabled={isDeletePending}
                          className="ui-btn ui-btn-danger min-h-0 px-3 py-1 text-xs"
                        >
                          {isDeletePending ? 'Deleting…' : 'Delete'}
                        </button>
                      </>
                    ) : (
                      <>
                        {next && (
                          <button
                            onClick={() => onStatusChange(r.id, next)}
                            disabled={isPending}
                            className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs"
                          >
                            Mark {next}
                          </button>
                        )}
                        {status === 'DRAFT' && (
                          <button
                            onClick={() => setRejectRecordId(r.id)}
                            className="ui-btn ui-btn-secondary min-h-0 px-3 py-1 text-xs"
                          >
                            Reject
                          </button>
                        )}
                      </>
                    )}
                  </div>
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
