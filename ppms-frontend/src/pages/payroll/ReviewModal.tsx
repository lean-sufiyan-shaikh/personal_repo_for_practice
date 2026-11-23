import { useState } from 'react'
import { withToggled } from '../../utils/setToggle'
import { useQuery } from '@tanstack/react-query'
import type { StaffMember } from '../../api/userApi'
import { payrollApi } from '../../api/payrollApi'
import type { GeneratePayrollRequest, PendingDiscrepancy } from '../../api/payrollApi'
import { Spinner } from '../../components/Spinner'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { discrepancyBadge } from '../../components/statusBadges'
import { MoneyText } from '../../components/AmountText'
import { fmtAmt, fmtDate } from './payrollFormat'

// ── Review + Discrepancy Modal ────────────────────────────────────────────────

interface ReviewModalProps {
  form: GeneratePayrollRequest
  selectedStaff: StaffMember | undefined
  pumpId: number
  formError: string | null
  isPending: boolean
  onClose: () => void
  onGenerate: (deductIds: number[]) => void
}

export function ReviewModal({ form, selectedStaff, pumpId, formError, isPending, onClose, onGenerate }: ReviewModalProps) {
  const [deductSet, setDeductSet] = useState<Set<number>>(new Set())

  const { data: pending = [], isLoading: pendingLoading } = useQuery({
    queryKey: ['pending-discrepancies', pumpId, form.userId],
    queryFn: () => payrollApi.getPendingDiscrepancies(pumpId, form.userId),
    enabled: form.userId > 0,
  })

  const toggleDeduct = (id: number) =>
    setDeductSet(prev => withToggled(prev, id))

  const totalDeductAmt = pending
    .filter(d => deductSet.has(d.id))
    .reduce((s, d) => s + d.discrepancyAmount, 0)

  return (
    <Modal
      title="Review Payroll"
      subtitle="Verify the details and handle any pending discrepancies."
      tone="info"
      size="lg"
      onClose={onClose}
      closeOnBackdropClick
      bodyClassName="space-y-4"
      footer={<>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">Back</button>
          <button
            onClick={() => onGenerate(Array.from(deductSet))}
            disabled={isPending}
            className="ui-btn ui-btn-primary"
          >
            {isPending
              ? <span className="flex items-center gap-1.5"><Spinner />Generating…</span>
              : 'Generate Payroll'}
          </button>
      </>}
    >
          {formError && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">{formError} Go back to modify the data and try again.</div>
          )}

          {/* Summary */}
          <div className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">Staff Member</span>
              <span className="font-medium text-ink">{selectedStaff ? `${selectedStaff.fullName} — ${selectedStaff.role}` : '—'}</span>
            </div>
            <div className="flex items-center justify-between px-4 py-2.5">
              <span className="text-ink-3 text-xs">Period</span>
              <span className="font-medium text-ink">{fmtDate(form.periodFrom)} – {fmtDate(form.periodTo)}</span>
            </div>
            {form.notes?.trim() && (
              <div className="flex items-center justify-between px-4 py-2.5">
                <span className="text-ink-3 text-xs">Notes</span>
                <span className="font-medium text-ink">{form.notes}</span>
              </div>
            )}
          </div>

          {/* Pending discrepancies */}
          {pendingLoading ? (
            <div className="text-xs text-ink-3 px-1">Checking pending discrepancies…</div>
          ) : pending.length > 0 ? (
            <div>
              <div className="flex items-center justify-between mb-2">
                <p className="text-xs font-semibold text-ink-2 uppercase tracking-wider">
                  Pending Discrepancies ({pending.length})
                </p>
                <p className="text-xs text-ink-3">Toggle to deduct from this salary</p>
              </div>
              <div className="divide-y divide-border border border-border rounded-card overflow-hidden">
                {pending.map((d: PendingDiscrepancy) => {
                  const isDeduct = deductSet.has(d.id)
                  return (
                    <div
                      key={d.id}
                      className={`flex items-center gap-3 px-4 py-3 transition-colors ${isDeduct ? 'bg-bad-bg' : 'bg-surface hover:bg-bg'}`}
                    >
                      {/* Info */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="text-xs font-mono text-ink-3">#{d.id}</span>
                          <span className="text-xs text-ink-2">{d.shiftDate}</span>
                          {(d.duName || d.duNumber != null) && (
                            <span className="text-xs text-ink-3">{d.duName ?? `DU #${d.duNumber}`}</span>
                          )}
                          <StatusBadge {...discrepancyBadge(d.discrepancyType)} />
                          <MoneyText
                            value={d.discrepancyAmount}
                            tone={d.discrepancyType === 'SHORT' ? 'bad' : 'warn'}
                            className="text-xs font-bold"
                          />
                        </div>
                        {d.discrepancyReason && (
                          <p className="text-xs text-ink-3 mt-0.5 italic truncate">{d.discrepancyReason}</p>
                        )}
                      </div>

                      {/* Toggle — an OVER discrepancy cannot be deducted from salary */}
                      {d.discrepancyType === 'SHORT' ? (
                        <button
                          type="button"
                          onClick={() => toggleDeduct(d.id)}
                          className={`shrink-0 text-xs font-semibold px-3 py-1.5 rounded-control border transition-colors ${
                            isDeduct
                              ? 'bg-bad border-bad text-white'
                              : 'border-control text-ink-2 hover:border-bad hover:text-bad'
                          }`}
                        >
                          {isDeduct ? 'Deduct ✓' : 'Skip'}
                        </button>
                      ) : (
                        <span className="shrink-0 text-xs text-ink-3">Not deductible</span>
                      )}
                    </div>
                  )
                })}
              </div>

              {deductSet.size > 0 && (
                <div className="mt-2 flex items-center justify-between px-1">
                  <span className="text-xs text-ink-3">{deductSet.size} deduction{deductSet.size !== 1 ? 's' : ''} selected</span>
                  <span className="text-xs font-semibold text-bad">−{fmtAmt(totalDeductAmt)} from net pay</span>
                </div>
              )}
              {deductSet.size === 0 && (
                <p className="text-xs text-ink-3 mt-1 px-1">All discrepancies set to Skip — they will remain pending for the next payroll run.</p>
              )}
            </div>
          ) : (
            <div className="flex items-center gap-2 px-1 text-xs text-ok">
              <span className="w-4 h-4 rounded-full bg-ok-bg flex items-center justify-center text-ok text-xs font-bold shrink-0">✓</span>
              No pending discrepancies for this staff member.
            </div>
          )}
    </Modal>
  )
}
