import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { RecordSafeCountRequest, SafeSummary } from '../../../api/cashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { MoneyText } from '../../../components/AmountText'
import { StillNeeded } from '../../../components/StillNeeded'
import { CashCountField } from '../../../components/cash/CashCountField'
import { EMPTY_COUNTS, toDenominations } from '../../../components/cash/denominations'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { parseApiError } from '../../../utils/apiError'
import { roundToPaise } from '../../../utils/money'
import { formatCurrency } from '../../../utils/format'
import { useToastStore } from '../../../store/toastStore'

interface Props {
  pumpId: number
  summary: SafeSummary
  onClose: () => void
}

/** Counts the safe, by note or as one total, against the ledger balance; a big difference notifies the owner. */
export function CountSafeDialog({ pumpId, summary, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [amount, setAmount] = useState('')
  const [counts, setCounts] = useState<DenominationCounts | null>(EMPTY_COUNTS)
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (request: RecordSafeCountRequest) => cashApi.recordSafeCount(pumpId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['safe', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['safeCounts', pumpId] })
      addToast(t('safe.count.saved'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('safe.count.failed'))),
  })

  const counted = amount.trim() === '' ? null : Number(amount)
  const isValid = counted !== null && Number.isFinite(counted) && counted >= 0
  const difference = isValid ? roundToPaise(counted - summary.balance) : null
  const beyondTolerance = difference !== null && Math.abs(difference) > summary.countTolerance
  const stillNeeded = isValid ? [] : [t('safe.count.needAmount')]

  const submit = () => {
    if (!isValid) return
    setError(null)
    mutation.mutate({
      countedAmount: counted,
      denominations: counts ? toDenominations(counts) : undefined,
      notes: notes.trim() || undefined,
    })
  }

  return (
    <Modal
      title={t('safe.count.title')}
      subtitle={t('safe.count.subtitle')}
      tone="info"
      size="md-wide"
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
          <LoadingButton type="button" onClick={submit} disabled={!isValid} isLoading={mutation.isPending}
            aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
            loadingLabel={t('safe.count.saving')} className="ui-btn-primary min-h-11">
            {t('safe.count.save')}
          </LoadingButton>
        </>
      }
    >
      <CashCountField label={t('safe.count.amountLabel')} amount={amount} onAmountChange={setAmount}
        counts={counts} onCountsChange={setCounts} />

      <LiveFigures
        label={t('safe.count.figuresLabel')}
        figures={[
          { label: t('safe.count.expected'), value: <MoneyText value={summary.balance} /> },
          { label: t('safe.count.counted'), value: <MoneyText value={isValid ? counted : null} /> },
          {
            label: t('safe.count.difference'),
            value: <MoneyText value={difference} showSign tone={difference === null || difference === 0 ? undefined : difference < 0 ? 'bad' : 'warn'} />,
            isResult: true,
          },
        ]}
      />
      {beyondTolerance && (
        <p className="ui-alert ui-alert-warning text-sm">{t('safe.count.notifyOwner', { amount: formatCurrency(summary.countTolerance) })}</p>
      )}

      <FormField label={<>{t('safe.count.notes')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>

      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
