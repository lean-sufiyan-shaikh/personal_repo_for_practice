import { useTranslation } from 'react-i18next'
import type { PumpExpense } from '../../api/expenseApi'
import { ConfirmButton } from '../../components/ConfirmButton'
import { StatusBadge } from '../../components/StatusBadge'
import { approvalStatusBadge } from '../../components/statusBadges'
import { MoneyText } from '../../components/AmountText'
import { codeLabel } from '../../constants/labels'
import { fmtDate } from './expenseFormat'

function ApprovalBadge({ expense }: { expense: PumpExpense }) {
  const { t } = useTranslation(['expenses', 'common'])
  const badge = approvalStatusBadge(expense.approvalStatus)
  const notes = expense.approvalStatus === 'REJECTED' ? expense.approvalNotes
    : expense.approvalStatus === 'VOIDED' ? expense.voidReason : null
  const label = notes ? t('list.rejectedWithNotes', { label: badge.label, notes }) : badge.label
  return <StatusBadge {...badge} label={label} wrap />
}

export interface ExpenseRowActions {
  canSubmit: boolean
  canReview: boolean
  canDelete: boolean
  canVoid: boolean
  busy: boolean
  onSubmit: () => void
  onApprove: () => void
  onReject: () => void
  onDelete: () => void
  onVoid: () => void
}

/** One expense: category, description, status, date and where it was paid from, with the actions open to the user. */
export function ExpenseRow({ expense: e, actions }: { expense: PumpExpense; actions: ExpenseRowActions }) {
  const { t } = useTranslation(['expenses', 'common'])
  const crossedOut = e.approvalStatus === 'REJECTED' || e.approvalStatus === 'VOIDED'
  return (
    <div className="px-5 py-3 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between sm:gap-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 min-w-0">
        <StatusBadge tone="neutral" label={codeLabel(e.category)} className="flex-shrink-0" />
        <div className="min-w-0">
          <div className="flex items-center gap-2 min-w-0 flex-wrap">
            <p className="text-sm text-ink break-words sm:truncate">{e.description}</p>
            <ApprovalBadge expense={e} />
          </div>
          <p className="text-xs text-ink-3">
            {fmtDate(e.expenseDate)}
            {e.paidFrom && <> · {t('list.paidFrom', { source: codeLabel(e.paidFrom) })}</>}
          </p>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2 sm:flex-shrink-0">
        <MoneyText value={e.amount}
          className={`text-sm font-semibold mr-auto sm:mr-0 ${crossedOut ? 'text-ink-3 line-through' : 'text-ink'}`} />
        {actions.canSubmit && (
          <button onClick={actions.onSubmit} disabled={actions.busy} className="ui-btn ui-btn-primary ui-btn-compact">
            {t('common:actions.submit')}
          </button>
        )}
        {actions.canReview && (
          <>
            <button onClick={actions.onApprove} disabled={actions.busy} className="ui-btn ui-btn-primary ui-btn-compact">
              {t('common:actions.approve')}
            </button>
            <button onClick={actions.onReject} disabled={actions.busy} className="ui-btn ui-btn-danger ui-btn-compact">
              {t('common:actions.reject')}
            </button>
          </>
        )}
        {actions.canVoid && (
          <button onClick={actions.onVoid} disabled={actions.busy} className="ui-btn ui-btn-secondary ui-btn-compact">
            {t('list.void')}
          </button>
        )}
        {actions.canDelete && (
          <ConfirmButton onConfirm={actions.onDelete} disabled={actions.busy} triggerAriaLabel={t('list.deleteExpense')} />
        )}
      </div>
    </div>
  )
}
