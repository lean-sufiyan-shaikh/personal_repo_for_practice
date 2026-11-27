import { useTranslation } from 'react-i18next'
import type { CreateExpenseRequest } from '../../api/expenseApi'
import { Modal } from '../../components/Modal'
import { StatusBadge } from '../../components/StatusBadge'
import { MoneyText } from '../../components/AmountText'
import { LoadingButton } from '../../components/LoadingButton'
import { codeLabel } from '../../constants/labels'
import { fmtDate } from './expenseFormat'

interface Props {
  form: CreateExpenseRequest
  isPending: boolean
  serverError: string | null
  onBack: () => void
  onSave: () => void
  onClose: () => void
}

/** The expense as it will be saved, with where it is paid from, before it is recorded. */
export function ExpenseReviewStep({ form, isPending, serverError, onBack, onSave, onClose }: Props) {
  const { t } = useTranslation(['expenses', 'common'])
  const rows: [string, React.ReactNode][] = [
    [t('form.category'), <StatusBadge key="category" tone="neutral" label={codeLabel(form.category)} />],
    [t('form.amount'), <MoneyText key="amount" value={form.amount} className="font-semibold text-ink" />],
    [t('form.description'), form.description.trim()],
    [t('form.expenseDate'), fmtDate(form.expenseDate)],
    [t('form.paidFrom'), codeLabel(form.paidFrom)],
  ]
  if (form.saveDraft) rows.push([t('review.savedAs'), <StatusBadge key="draft" tone="neutral" label={t('list.statusDraft')} />])

  return (
    <Modal
      tone="info"
      title={t('review.title')}
      subtitle={`${codeLabel(form.category)} · ${fmtDate(form.expenseDate)}`}
      size="md-wide"
      onClose={onClose}
      canClose={!isPending}
      bodyClassName="space-y-4"
      footer={<>
        <button type="button" onClick={onBack} disabled={isPending} className="ui-btn ui-btn-secondary">
          {t('common:actions.edit')}
        </button>
        <LoadingButton type="button" isLoading={isPending} loadingLabel={t('common:actions.saving')} onClick={onSave}
          className="ui-btn-primary">
          {t('review.add')}
        </LoadingButton>
      </>}
    >
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
      <dl className="ui-card-plain ui-card-muted divide-y divide-border overflow-hidden text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-center justify-between gap-3 px-4 py-2.5">
            <dt className="text-ink-2">{label}</dt>
            <dd className="text-right font-medium text-ink break-words">{value}</dd>
          </div>
        ))}
      </dl>
      {(form.paidFrom === 'SAFE' || form.paidFrom === 'PETTY_CASH') && !form.saveDraft && (
        <p className="ui-alert ui-alert-warning text-sm">{t('review.cashMoves', { source: codeLabel(form.paidFrom) })}</p>
      )}
    </Modal>
  )
}
