import { useTranslation } from 'react-i18next'
import type { PayrollRecord, PayrollStatus } from '../../api/payrollApi'
import { ConfirmButton } from '../../components/ConfirmButton'
import { MoneyText } from '../../components/AmountText'
import { formatDecimal } from '../../utils/format'
import { NEXT_STATUS, fmtAmt, fmtDate } from './payrollFormat'

interface PayrollRecordCardProps {
  record: PayrollRecord
  staffName: string | undefined
  onStatusChange: (recordId: number, status: PayrollStatus) => void
  onMarkPaid: (record: PayrollRecord) => void
  onDelete: (recordId: number) => void
  isPending: boolean
  isDeletePending: boolean
}

/** One payroll record: its pay, deductions, how it was paid and the next step. */
export function PayrollRecordCard({
  record: r, staffName, onStatusChange, onMarkPaid, onDelete, isPending, isDeletePending,
}: PayrollRecordCardProps) {
  const { t } = useTranslation(['payroll', 'common'])
  const next = NEXT_STATUS[r.status]
  const shiftDeductions = r.deductions - r.openingDeductions
  const paidDate = r.paidDate ? fmtDate(r.paidDate) : null
  const paidMode = r.paymentMode ? t(`markPaid.modes.${r.paymentMode}`) : null
  const paidLine = paidDate == null ? null
    : paidMode == null ? t('group.paidOn', { date: paidDate })
      : r.paymentReference ? t('group.paidOnByWithReference', { date: paidDate, mode: paidMode, reference: r.paymentReference })
        : t('group.paidOnBy', { date: paidDate, mode: paidMode })

  return (
    <div className="px-5 py-4 space-y-2.5 bg-surface">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-ink">{staffName ?? t('group.staffNumber', { id: r.userId })}</span>
          <span className="text-xs text-ink-3">{fmtDate(r.periodFrom)} – {fmtDate(r.periodTo)}</span>
        </div>
        <div className="text-right flex-shrink-0">
          <MoneyText value={r.netPay} className="block text-base font-bold text-ink" />
          {r.deductions > 0 && <MoneyText value={r.grossAmount} className="block text-xs text-ink-3 line-through" />}
        </div>
      </div>

      <div className="bg-surface-sunken rounded-card border border-border divide-y divide-border text-xs">
        {r.salaryType === 'DAILY' ? (
          <>
            <div className="flex items-center justify-between px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-info shrink-0" />
                <span className="text-ink-3">{t('group.totalDays')}</span>
              </div>
              <span className="font-medium text-ink">{r.totalDays ?? 0}</span>
            </div>
            <div className="flex items-center justify-between px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-warn shrink-0" />
                <span className="text-ink-3">{t('group.leaveDays')}</span>
              </div>
              <span className="font-medium text-warn">− {r.leaveDays ?? 0}</span>
            </div>
            <div className="flex items-center justify-between px-3 py-2">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-ok shrink-0" />
                <span className="text-ink-3">{t('group.daysWorked')}</span>
                {r.dailyRateSnapshot != null && (
                  <span className="text-ink-3">{t('group.perDay', { rate: fmtAmt(r.dailyRateSnapshot) })}</span>
                )}
              </div>
              <span className="font-medium text-ink">{t('group.days', { count: r.daysWorked ?? 0 })}</span>
            </div>
          </>
        ) : (
          <>
            {r.shift1Shifts > 0 && (
              <div className="flex items-center justify-between px-3 py-2">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-primary shrink-0" />
                  <span className="text-ink-3">{t('group.night')}</span>
                  <span className="text-ink-3">{t('group.shiftsHours', { count: r.shift1Shifts, hours: formatDecimal(r.shift1Hours) })}</span>
                  {r.shift1RateSnapshot != null && (
                    <span className="text-ink-3">{t('group.perHour', { rate: fmtAmt(r.shift1RateSnapshot) })}</span>
                  )}
                </div>
                <MoneyText value={r.shift1RateSnapshot != null ? r.shift1Hours * r.shift1RateSnapshot : null} className="font-medium text-ink" />
              </div>
            )}
            {r.standardShifts > 0 && (
              <div className="flex items-center justify-between px-3 py-2">
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full bg-accent shrink-0" />
                  <span className="text-ink-3">{t('group.day')}</span>
                  <span className="text-ink-3">{t('group.shiftsHours', { count: r.standardShifts, hours: formatDecimal(r.standardHours) })}</span>
                  {r.standardRateSnapshot != null && (
                    <span className="text-ink-3">{t('group.perHour', { rate: fmtAmt(r.standardRateSnapshot) })}</span>
                  )}
                </div>
                <MoneyText value={r.standardRateSnapshot != null ? r.standardHours * r.standardRateSnapshot : null} className="font-medium text-ink" />
              </div>
            )}
            {r.shift1Shifts === 0 && r.standardShifts === 0 && (
              <p className="px-3 py-2 text-ink-3">{t('group.noShifts')}</p>
            )}
          </>
        )}
        {shiftDeductions > 0 && (
          <div className="flex items-center justify-between px-3 py-2 bg-bad-bg">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-bad shrink-0" />
              <span className="text-ink-3">{t('group.deductions')}</span>
              <span className="text-xs text-ink-3">{t('group.shortfall')}</span>
            </div>
            <MoneyText value={-shiftDeductions} showSign tone="bad" className="font-medium" />
          </div>
        )}
        {r.openingDeductions > 0 && (
          <div className="flex items-center justify-between px-3 py-2 bg-bad-bg">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-bad shrink-0" />
              <span className="text-ink-3">{t('group.openingDeductions')}</span>
            </div>
            <MoneyText value={-r.openingDeductions} showSign tone="bad" className="font-medium" />
          </div>
        )}
        {(r.advanceRecovered ?? 0) > 0 && (
          <div className="flex items-center justify-between px-3 py-2 bg-bad-bg">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-bad shrink-0" />
              <span className="text-ink-3">{t('group.advanceRecovered')}</span>
            </div>
            <MoneyText value={-(r.advanceRecovered ?? 0)} showSign tone="bad" className="font-medium" />
          </div>
        )}
      </div>

      {r.uncoveredDeductions > 0 && (
        <p className="text-xs text-warn">
          {t(r.carriedToPayrollId != null ? 'group.carriedForward' : 'group.toCarryForward', { amount: fmtAmt(r.uncoveredDeductions) })}
        </p>
      )}

      <div className="flex items-center justify-between gap-2">
        <p className="text-xs text-ink-3">
          {r.salaryType === 'DAILY'
            ? t('group.daysWorkedTotal', { count: r.daysWorked ?? 0 })
            : t('group.shiftsTotal', { count: r.totalShifts })}
          {r.notes && ` · ${r.notes}`}
          {paidLine && ` · ${paidLine}`}
        </p>
        <div className="flex items-center gap-2">
          {next === 'PAID' ? (
            <button type="button" onClick={() => onMarkPaid(r)} disabled={isPending} className="ui-btn ui-btn-primary ui-btn-compact">
              {t('group.mark.PAID')}
            </button>
          ) : next && (
            <button type="button" onClick={() => onStatusChange(r.id, next)} disabled={isPending}
              className="ui-btn ui-btn-primary ui-btn-compact">
              {t(`group.mark.${next}`)}
            </button>
          )}
          {r.status === 'DRAFT' && (
            <ConfirmButton
              onConfirm={() => onDelete(r.id)}
              disabled={isDeletePending}
              triggerLabel={t('common:actions.delete')}
              triggerAriaLabel={t('common:actions.delete')}
              prompt={t('group.deletePrompt')}
            />
          )}
        </div>
      </div>
    </div>
  )
}
