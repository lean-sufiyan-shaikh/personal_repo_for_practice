import { useState } from 'react'
import { History, Printer } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../../store/authStore'
import type { Shift } from '../../../types/shift'
import { IconButton } from '../../../components/IconButton'
import { CreditEntriesModal } from '../modals/CreditEntriesModal'
import { HandoverModal } from '../modals/HandoverModal'
import { ResolveDiscrepancyModal } from '../modals/ResolveDiscrepancyModal'
import { awaitingOwnerApprovalLabel, discrepancyAction, fmtAmt, investigationLabel } from '../shiftLabels'
import { StatusBadge } from '../../../components/StatusBadge'
import { shiftStatusBadge } from '../../../components/statusBadges'
import { MoneyText } from '../../../components/AmountText'
import { codeLabel } from '../../../constants/labels'

// ── History row ───────────────────────────────────────────────────────────────
// Payment breakdown is always visible for closed shifts.
// Credit amount is clickable — opens a popup modal listing all client credit entries.

export function HistoryRow({ shift }: { shift: Shift }) {
  const { t } = useTranslation(['shifts', 'common'])
  const { user } = useAuthStore()

  const resolveAction = discrepancyAction(shift, user?.role)

  const [creditModalOpen,    setCreditModalOpen]    = useState(false)
  const [resolveModalOpen,   setResolveModalOpen]   = useState(false)
  const [handoverOpen,       setHandoverOpen]       = useState(false)

  const isClosed         = shift.totalAmountDue != null
  const digitalTotal     = (shift.upiCollected ?? 0) + (shift.cardCollected ?? 0)
  const hasCreditEntries = (shift.creditEntries?.length ?? 0) > 0
  const creditAmt        = shift.creditTotal ?? 0
  const investigation    = investigationLabel(shift)

  return (
    <>
      {/* ── Main data row ─────────────────────────────────────────────────── */}
      <tr>
        <td className="px-5 py-3 text-ink-3 text-xs font-mono hidden md:table-cell">{shift.id}</td>
        <td className="px-5 py-3">
          <span className="text-xs text-ink-3 block">
            {shift.duName ?? `DU #${shift.duNumber}`}
          </span>
          <span className="font-semibold text-ink bg-surface-sunken px-2 py-0.5 rounded-control text-xs">
            {shift.nozzles.map((n) => `#${n.nozzleNumber} ${codeLabel(n.fuelType)}`).join(', ')}
          </span>
        </td>
        <td className="px-5 py-3 hidden md:table-cell">
          {shift.shiftWindow ? (
            <StatusBadge tone="info" label={shift.shiftWindow} />
          ) : (
            <span className="text-xs text-ink-3">—</span>
          )}
        </td>
        <td className="px-5 py-3 text-sm text-ink font-medium">{shift.operatorName}</td>
        <td className="px-5 py-3 text-xs text-ink-3">{shift.shiftDate}</td>
        <td className="px-5 py-3 text-sm font-semibold text-ink">
          <MoneyText value={shift.totalAmountDue} />
        </td>
        <td className="px-5 py-3">
          <div className="flex flex-wrap items-center gap-1">
            <StatusBadge {...shiftStatusBadge(shift.status)} />
            {shift.isBackfilled && <StatusBadge tone="neutral" icon={History} label={t('history.backfilled')} />}
          </div>
        </td>
        <td className="px-5 py-3 hidden md:table-cell">
          {shift.discrepancyAmount != null ? (
            <span className={`text-xs font-semibold tabular-nums ${
              shift.discrepancyType === 'SHORT' ? 'text-bad' : 'text-warn'
            }`}>
              {shift.discrepancyType === 'SHORT' ? t('common:badges.short') : shift.discrepancyType === 'OVER' ? t('common:badges.over') : shift.discrepancyType} {fmtAmt(shift.discrepancyAmount)}
            </span>
          ) : (
            <span className="text-xs text-ink-3">—</span>
          )}
        </td>
        <td className="px-4 py-3 text-right">
          <div className="flex items-center justify-end gap-2">
            {resolveAction === 'resolve' && (
              <button onClick={() => setResolveModalOpen(true)} className="ui-btn ui-btn-danger min-h-0 px-3 py-1.5 text-xs">
                {t('history.resolve')}
              </button>
            )}
            {resolveAction === 'approve' && (
              <button onClick={() => setResolveModalOpen(true)} className="ui-btn ui-btn-warning min-h-0 px-3 py-1.5 text-xs">
                {t('common:actions.approve')}
              </button>
            )}
            {resolveAction === 'awaiting-owner' && (
              <span className="text-xs text-warn font-medium">{awaitingOwnerApprovalLabel()}</span>
            )}
            {shift.discrepancyResolution && (
              <span className="text-xs text-ink-3">{codeLabel(shift.discrepancyResolution)}</span>
            )}
            {investigation && (
              <span className="text-xs text-warn" title={shift.investigationNote ?? undefined}>{investigation}</span>
            )}
            {isClosed && (
              <IconButton
                onClick={() => setHandoverOpen(true)}
                className="text-ink-3 hover:text-ink-2 transition-colors"
                label={t('history.printHandover')}
                icon={<Printer size={14} strokeWidth={2} />}
              />
            )}
          </div>
        </td>
      </tr>

      {/* ── Payment breakdown row — always visible for closed shifts ──────── */}
      {isClosed && (
        <tr className="bg-surface-sunken">
          <td colSpan={9} className="px-5 pb-3 pt-1">
            <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-xs">

              {/* Cash */}
              <div className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-ok shrink-0" />
                <span className="text-ink-3">{t('common:paymentMethods.CASH')}</span>
                <MoneyText value={shift.cashCollected} className="font-semibold text-ink" />
              </div>

              {/* UPI + Card */}
              <div className="flex items-center gap-1.5 flex-wrap">
                <span className="w-2 h-2 rounded-full bg-info shrink-0" />
                <span className="text-ink-3">{t('history.payment.upiCard')}</span>
                <MoneyText value={digitalTotal > 0 ? digitalTotal : null} className="font-semibold text-ink" />
                {(shift.upiCollected ?? 0) > 0 && (
                  <span className="text-ink-3 bg-info-bg px-1.5 py-0.5 rounded-control tabular-nums">
                    {t('history.payment.upiAmount', { amount: fmtAmt(shift.upiCollected) })}
                  </span>
                )}
                {(shift.cardCollected ?? 0) > 0 && (
                  <span className="text-ink-3 bg-info-bg px-1.5 py-0.5 rounded-control tabular-nums">
                    {t('history.payment.cardAmount', { amount: fmtAmt(shift.cardCollected) })}
                  </span>
                )}
              </div>

              {/* Credit — clickable pill that opens the modal */}
              <div className="flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-warn shrink-0" />
                <span className="text-ink-3">{t('common:paymentMethods.CREDIT')}</span>
                {creditAmt > 0 ? (
                  hasCreditEntries ? (
                    <button
                      type="button"
                      onClick={() => setCreditModalOpen(true)}
                      className="flex items-center gap-1.5 font-semibold px-2 py-0.5 rounded-control bg-warn-bg text-warn hover:text-warn-strong transition-colors tabular-nums"
                      title={t('history.payment.viewCredit')}
                    >
                      {fmtAmt(creditAmt)}
                      <span className="text-warn text-xs">
                        · {t('history.payment.clientCount', { count: shift.creditEntries.length })} ↗
                      </span>
                    </button>
                  ) : (
                    <MoneyText value={creditAmt} className="font-semibold text-warn" />
                  )
                ) : (
                  <span className="text-ink-3">—</span>
                )}
              </div>
            </div>
          </td>
        </tr>
      )}

      {/* ── Credit entries popup modal ─────────────────────────────────────── */}
      {creditModalOpen && hasCreditEntries && (
        <CreditEntriesModal
          shift={shift}
          entries={shift.creditEntries}
          creditTotal={creditAmt}
          onClose={() => setCreditModalOpen(false)}
        />
      )}

      {/* ── Resolve discrepancy modal ──────────────────────────────────────── */}
      {resolveModalOpen && (
        <ResolveDiscrepancyModal
          shift={shift}
          onClose={() => setResolveModalOpen(false)}
        />
      )}

      {/* ── Handover print modal ──────────────────────────────────────────── */}
      {handoverOpen && (
        <HandoverModal shift={shift} onClose={() => setHandoverOpen(false)} />
      )}
    </>
  )
}
