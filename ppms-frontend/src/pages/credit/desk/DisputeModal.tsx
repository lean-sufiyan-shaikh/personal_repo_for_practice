import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { CreditTransaction } from '../../../api/creditApi'
import { creditDeskApi } from '../../../api/creditDeskApi'
import type { CreditDebitType } from '../../../api/creditDeskApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { NumericInput } from '../../../components/NumericInput'
import { parseApiError } from '../../../utils/apiError'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { formatCurrency } from '../../../utils/format'
import { roundToPaise } from '../../../utils/money'

type Resolution = 'ACCEPTED' | 'CREDITED'

interface DisputeModalProps {
  pumpId: number
  debit: CreditTransaction
  onDone: () => void
  onClose: () => void
}

/**
 * Raises a dispute on a debit, or resolves the open one: ACCEPTED keeps the debit and its interest, CREDITED issues a
 * credit note for all or part of it.
 */
export function DisputeModal({ pumpId, debit, onDone, onClose }: DisputeModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const disputeId = debit.openDisputeId ?? null
  const [text, setText] = useState('')
  const [resolution, setResolution] = useState<Resolution>('ACCEPTED')
  const [creditAmount, setCreditAmount] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)

  const mutation = useMutation({
    mutationFn: (key: string) => disputeId == null
      ? creditDeskApi.raiseDispute(pumpId, { debitType: debit.type as CreditDebitType, debitId: debit.referenceId, reason: text.trim() })
      : creditDeskApi.resolveDispute(pumpId, disputeId, {
          resolution, note: text.trim(),
          creditAmount: resolution === 'CREDITED' && creditAmount.trim() !== '' ? roundToPaise(Number(creditAmount)) : undefined,
        }, key),
    onSuccess: () => {
      onDone()
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('desk.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (text.trim() === '') {
      setError(t('desk.reasonRequired'))
      return
    }
    const attempt = attemptFor(submitAttempt, { disputeId, resolution, text, creditAmount })
    setSubmitAttempt(attempt)
    mutation.mutate(attempt.key)
  }

  return (
    <Modal
      title={disputeId == null ? t('desk.dispute.raiseTitle') : t('desk.dispute.resolveTitle')}
      subtitle={formatCurrency(debit.amount)}
      tone="warning"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')}
            className="ui-btn-primary">
            {disputeId == null ? t('desk.dispute.raise') : t('desk.dispute.resolve')}
          </LoadingButton>
        </>
      }
    >
      <p className="text-sm text-ink-2">{disputeId == null ? t('desk.dispute.raiseHelp') : t('desk.dispute.resolveHelp')}</p>
      {disputeId != null && (
        <fieldset className="space-y-2">
          <legend className="ui-label">{t('desk.dispute.resolution')}</legend>
          {(['ACCEPTED', 'CREDITED'] as const).map(option => (
            <label key={option} className="flex items-center gap-2 text-sm text-ink">
              <input type="radio" name="resolution" checked={resolution === option} onChange={() => setResolution(option)} />
              {t(`desk.dispute.${option}`)}
            </label>
          ))}
        </fieldset>
      )}
      {disputeId != null && resolution === 'CREDITED' && (
        <FormField label={t('desk.dispute.creditAmount')} help={t('desk.dispute.creditAmountHelp')}>
          <NumericInput unit="₹" min="0.01" step="0.01" value={creditAmount} onChange={(e) => setCreditAmount(e.target.value)} />
        </FormField>
      )}
      <FormField label={disputeId == null ? t('desk.reason') : t('desk.dispute.note')}>
        <textarea value={text} rows={2} maxLength={500} className="resize-none" data-autofocus
          onChange={(e) => { setText(e.target.value); setError(null) }} />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
