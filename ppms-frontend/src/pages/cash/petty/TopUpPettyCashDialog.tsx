import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pettyCashApi } from '../../../api/pettyCashApi'
import type { PettyCashSummary, PettyCashTopUpRequest } from '../../../api/pettyCashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { MoneyText } from '../../../components/AmountText'
import { NumericInput } from '../../../components/NumericInput'
import { StillNeeded } from '../../../components/StillNeeded'
import { parseApiError } from '../../../utils/apiError'
import { roundToPaise } from '../../../utils/money'
import { istDateString } from '../../../utils/date'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { useToastStore } from '../../../store/toastStore'

interface Props {
  pumpId: number
  summary: PettyCashSummary
  onClose: () => void
}

/** Moves cash from the safe into the petty cash box, starting from the amount that refills it to its float. */
export function TopUpPettyCashDialog({ pumpId, summary, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [today] = useState(istDateString)
  const [amount, setAmount] = useState(summary.suggestedTopUp > 0 ? String(summary.suggestedTopUp) : '')
  const [date, setDate] = useState(today)
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState<SubmitAttempt | null>(null)

  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: PettyCashTopUpRequest; key: string }) => pettyCashApi.topUp(pumpId, request, key),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pettyCash', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['safe', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['safeLedger', pumpId] })
      addToast(t('petty.topUpSaved'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('petty.topUpFailed'))),
  })

  const value = Number(amount)
  const isAmountValid = amount.trim() !== '' && Number.isFinite(value) && value > 0
  const stillNeeded = [
    ...(isAmountValid ? [] : [t('petty.needAmount')]),
    ...(date === '' ? [t('petty.needDate')] : []),
  ]

  const submit = () => {
    if (stillNeeded.length > 0) return
    setError(null)
    const request: PettyCashTopUpRequest = {
      amount: roundToPaise(value), entryDate: date === today ? undefined : date, notes: notes.trim() || undefined,
    }
    const next = attemptFor(attempt, request)
    setAttempt(next)
    mutation.mutate({ request, key: next.key })
  }

  return (
    <Modal title={t('petty.topUpTitle')} subtitle={t('petty.topUpSubtitle')} tone="info" size="md" fullScreenOnPhone
      onClose={onClose} canClose={!mutation.isPending} bodyClassName="space-y-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" onClick={submit} disabled={stillNeeded.length > 0} isLoading={mutation.isPending}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          loadingLabel={t('common:actions.saving')} className="ui-btn-primary min-h-11">
          {t('petty.topUp')}
        </LoadingButton>
      </>}>
      <FormField label={t('petty.amount')}>
        <NumericInput unit="₹" min="0" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} data-autofocus />
      </FormField>
      <FormField label={t('petty.date')}>
        <input type="date" value={date} max={today} onChange={e => setDate(e.target.value)} />
      </FormField>
      <LiveFigures label={t('petty.figuresLabel')} figures={[
        { label: t('petty.balance'), value: <MoneyText value={summary.balance} /> },
        { label: t('petty.afterTopUp'), value: <MoneyText value={isAmountValid ? roundToPaise(summary.balance + value) : null} />, isResult: true },
      ]} />
      <FormField label={<>{t('safe.count.notes')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={2} maxLength={300} className="resize-none" />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
