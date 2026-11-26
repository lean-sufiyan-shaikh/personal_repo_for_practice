import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { RecordCashDropRequest } from '../../../api/cashApi'
import type { Shift } from '../../../types/shift'
import { Modal } from '../../../components/Modal'
import { LoadingButton } from '../../../components/LoadingButton'
import { StillNeeded } from '../../../components/StillNeeded'
import { DraftRestoredNotice } from '../../../components/DraftRestoredNotice'
import { CashCountField } from '../../../components/cash/CashCountField'
import { EMPTY_COUNTS, toDenominations } from '../../../components/cash/denominations'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { readDraft, removeDraft, useSaveDraft, writeDraft } from '../../../hooks/useSaveDraft'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { formatCurrency } from '../../../utils/format'
import { emptyNum } from '../components/creditRow'

interface CashDropDraft {
  amount: string
  counts: DenominationCounts | null
  submitAttempt: SubmitAttempt | null
}

/**
 * The operator moves cash from the bag into the safe. The drop waits for a manager to count and confirm it; the
 * Idempotency-Key is kept with the draft so a resend after a timeout never records the drop twice.
 */
export function RecordDropModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const draftName = `cash-drop:${shift.id}`
  const [restoredDraft, setRestoredDraft] = useState(() => readDraft<CashDropDraft>(draftName))
  const [amount, setAmount] = useState(() => restoredDraft?.values.amount ?? '')
  const [counts, setCounts] = useState<DenominationCounts | null>(() => restoredDraft ? restoredDraft.values.counts : EMPTY_COUNTS)
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(() => restoredDraft?.values.submitAttempt ?? null)
  const [error, setError] = useState<string | null>(null)

  const draftValues: CashDropDraft = { amount, counts, submitAttempt }
  useSaveDraft(draftName, draftValues, amount !== '')

  const mutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: RecordCashDropRequest; idempotencyKey: string }) =>
      cashApi.recordDrop(shift.pumpId, shift.id, request, idempotencyKey),
    onSuccess: (drop) => {
      removeDraft(draftName)
      queryClient.invalidateQueries({ queryKey: ['cashDrops', shift.pumpId, shift.id] })
      addToast(t('drops.recorded', { amount: formatCurrency(drop.amount) }), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('drops.recordFailed'))),
  })

  const amountValue = emptyNum(amount)
  const stillNeeded = amountValue > 0 ? [] : [t('drops.stillNeededAmount')]

  const submit = () => {
    if (amountValue <= 0) return
    setError(null)
    const request: RecordCashDropRequest = {
      amount: amountValue,
      ...(counts && { denominations: toDenominations(counts) }),
    }
    const attempt = attemptFor(submitAttempt, request)
    setSubmitAttempt(attempt)
    writeDraft(draftName, { ...draftValues, submitAttempt: attempt })
    mutation.mutate({ request, idempotencyKey: attempt.key })
  }

  const discardRestored = () => {
    removeDraft(draftName)
    setRestoredDraft(null)
    setAmount('')
    setCounts(EMPTY_COUNTS)
    setSubmitAttempt(null)
  }

  return (
    <Modal
      title={t('drops.title')}
      subtitle={t('drops.subtitle', { du: shift.duName ?? shift.duNumber, operator: shift.operatorName })}
      tone="info"
      size="md"
      fullScreenOnPhone
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <StillNeeded id={stillNeededId} items={stillNeeded} />
          <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
            {t('common:actions.cancel')}
          </button>
          <LoadingButton type="button" onClick={submit} disabled={amountValue <= 0} isLoading={mutation.isPending}
            aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
            loadingLabel={t('drops.saving')} className="ui-btn-primary min-h-11">
            {t('drops.save')}
          </LoadingButton>
        </>
      }
    >
      {restoredDraft && <DraftRestoredNotice savedAt={restoredDraft.savedAt} onDiscard={discardRestored} />}
      <p className="text-sm text-ink-2">{t('drops.help')}</p>
      <CashCountField label={t('drops.amount')} amount={amount} onAmountChange={setAmount} counts={counts} onCountsChange={setCounts} />
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
