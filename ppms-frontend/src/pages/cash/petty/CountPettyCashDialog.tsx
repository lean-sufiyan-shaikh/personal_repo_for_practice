import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pettyCashApi } from '../../../api/pettyCashApi'
import type { PettyCashCountRequest, PettyCashSummary } from '../../../api/pettyCashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { MoneyText } from '../../../components/AmountText'
import { NumericInput } from '../../../components/NumericInput'
import { StillNeeded } from '../../../components/StillNeeded'
import { parseApiError } from '../../../utils/apiError'
import { roundToPaise } from '../../../utils/money'
import { formatCurrency } from '../../../utils/format'
import { formatIstDate, istDateString } from '../../../utils/date'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { useToastStore } from '../../../store/toastStore'

interface Props {
  pumpId: number
  summary: PettyCashSummary
  /** The business day to count for, e.g. the day the Day Close checklist is closing. */
  initialDate?: string | null
  onClose: () => void
}

/** Counts the petty cash box against its ledger; a difference beyond the tolerance notifies the owner. */
export function CountPettyCashDialog({ pumpId, summary, initialDate, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [today] = useState(istDateString)
  const [date, setDate] = useState(initialDate && initialDate <= today ? initialDate : today)
  const [amount, setAmount] = useState('')
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState<SubmitAttempt | null>(null)

  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: PettyCashCountRequest; key: string }) => pettyCashApi.recordCount(pumpId, request, key),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['pettyCash', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['dayClosePreview', pumpId] })
      addToast(t('petty.countSaved'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('petty.countFailed'))),
  })

  const counted = Number(amount)
  const isAmountValid = amount.trim() !== '' && Number.isFinite(counted) && counted >= 0
  const isToday = date === today
  const difference = isAmountValid && isToday ? roundToPaise(counted - summary.balance) : null
  const stillNeeded = [
    ...(isAmountValid ? [] : [t('petty.needAmount')]),
    ...(date === '' ? [t('petty.needDate')] : []),
  ]

  const submit = () => {
    if (stillNeeded.length > 0) return
    setError(null)
    const request: PettyCashCountRequest = {
      countedAmount: roundToPaise(counted), countForDate: isToday ? undefined : date, notes: notes.trim() || undefined,
    }
    const next = attemptFor(attempt, request)
    setAttempt(next)
    mutation.mutate({ request, key: next.key })
  }

  return (
    <Modal title={t('petty.countTitle')}
      subtitle={isToday ? t('petty.countSubtitle') : t('safe.count.subtitleFor', { date: formatIstDate(date) })}
      tone="info" size="md" fullScreenOnPhone onClose={onClose} canClose={!mutation.isPending} bodyClassName="space-y-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" onClick={submit} disabled={stillNeeded.length > 0} isLoading={mutation.isPending}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          loadingLabel={t('safe.count.saving')} className="ui-btn-primary min-h-11">
          {t('safe.count.save')}
        </LoadingButton>
      </>}>
      <FormField label={t('petty.countFor')}>
        <input type="date" value={date} max={today} onChange={e => setDate(e.target.value)} />
      </FormField>
      <FormField label={t('petty.counted')}>
        <NumericInput unit="₹" min="0" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} data-autofocus />
      </FormField>
      {isToday ? (
        <LiveFigures label={t('safe.count.figuresLabel')} figures={[
          { label: t('safe.count.expected'), value: <MoneyText value={summary.balance} /> },
          { label: t('safe.count.counted'), value: <MoneyText value={isAmountValid ? counted : null} /> },
          { label: t('safe.count.difference'), isResult: true,
            value: <MoneyText value={difference} showSign tone={!difference ? undefined : difference < 0 ? 'bad' : 'warn'} /> },
        ]} />
      ) : (
        <p className="text-sm text-ink-3">{t('petty.pastDayHint')}</p>
      )}
      {difference !== null && Math.abs(difference) > summary.countTolerance && (
        <p className="ui-alert ui-alert-warning text-sm">{t('safe.count.notifyOwner', { amount: formatCurrency(summary.countTolerance) })}</p>
      )}
      <FormField label={<>{t('safe.count.notes')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
