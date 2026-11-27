import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { ChargebackKind, RecordChargebackRequest, Settlement, SettlementPaymentType } from '../../api/paymentSettlementApi'
import { Modal } from '../../components/Modal'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { NumericInput } from '../../components/NumericInput'
import { SearchableSelect } from '../../components/SearchableSelect'
import { StillNeeded } from '../../components/StillNeeded'
import { codeLabel } from '../../constants/labels'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { roundToPaise } from '../../utils/money'
import { istDateString } from '../../utils/date'
import { attemptFor } from '../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../utils/idempotencyKey'
import { TYPES, fmtAmt, fmtDate, typeLabel } from './settlementFormat'

interface Props {
  pumpId: number
  /** The settlement the money was taken from; without one the payment type and collection day are asked for. */
  settlement: Settlement | null
  onClose: () => void
}

const KINDS: ChargebackKind[] = ['CHARGEBACK', 'REFUND']

/** Owner/Admin records money the bank took back; it lowers net received and is a cost of its day. */
export function RecordChargebackDialog({ pumpId, settlement, onClose }: Props) {
  const { t } = useTranslation(['settlements', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [today] = useState(istDateString)
  const [kind, setKind] = useState<ChargebackKind>('CHARGEBACK')
  const [paymentType, setPaymentType] = useState<SettlementPaymentType>(settlement?.paymentType ?? 'CARD')
  const [date, setDate] = useState(today)
  const [collectionDate, setCollectionDate] = useState('')
  const [amount, setAmount] = useState('')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState<SubmitAttempt | null>(null)

  const mutation = useMutation({
    mutationFn: ({ request, key }: { request: RecordChargebackRequest; key: string }) =>
      paymentSettlementApi.recordChargeback(pumpId, request, key),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['settlements-daily', pumpId] })
      qc.invalidateQueries({ queryKey: ['chargebacks', pumpId] })
      addToast(t('chargeback.saved'), 'success')
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('chargeback.failed'))),
  })

  const value = Number(amount)
  const remaining = settlement ? settlement.netAfterChargebacks : null
  const isAmountValid = amount.trim() !== '' && Number.isFinite(value) && value > 0 && (remaining === null || value <= remaining)
  const stillNeeded = [
    ...(isAmountValid ? [] : [t('chargeback.needAmount')]),
    ...(reason.trim() ? [] : [t('chargeback.needReason')]),
    ...(settlement || collectionDate ? [] : [t('chargeback.needCollectionDate')]),
  ]

  const submit = () => {
    if (stillNeeded.length > 0) return
    setError(null)
    const request: RecordChargebackRequest = {
      kind, chargebackDate: date, amount: roundToPaise(value), reason: reason.trim(),
      reference: reference.trim() || undefined,
      ...(settlement ? { settlementId: settlement.id } : { paymentType, collectionDate }),
    }
    const next = attemptFor(attempt, request)
    setAttempt(next)
    mutation.mutate({ request, key: next.key })
  }

  return (
    <Modal tone="warning" title={t('chargeback.title')} size="md-wide" fullScreenOnPhone onClose={onClose}
      canClose={!mutation.isPending} bodyClassName="space-y-4"
      subtitle={settlement
        ? t('chargeback.againstSettlement', { type: typeLabel(settlement.paymentType), date: fmtDate(settlement.settlementDate), net: fmtAmt(settlement.netAfterChargebacks) })
        : t('chargeback.subtitle')}
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" onClick={submit} disabled={stillNeeded.length > 0} isLoading={mutation.isPending}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          loadingLabel={t('common:actions.saving')} className="ui-btn-danger min-h-11">
          {t('chargeback.save')}
        </LoadingButton>
      </>}>
      <p className="text-sm text-ink-2">{t('chargeback.explanation')}</p>
      <div className="grid gap-4 sm:grid-cols-2">
        <SearchableSelect label={t('chargeback.kind')} value={kind} onChange={v => setKind(v as ChargebackKind)}
          options={KINDS.map(k => ({ value: k, label: codeLabel(k) }))} />
        {!settlement && (
          <SearchableSelect label={t('details.paymentType')} value={paymentType}
            onChange={v => setPaymentType(v as SettlementPaymentType)} options={TYPES.map(type => ({ value: type, label: typeLabel(type) }))} />
        )}
        <FormField label={t('chargeback.date')}>
          <input type="date" value={date} max={today} onChange={e => setDate(e.target.value)} />
        </FormField>
        {!settlement && (
          <FormField label={t('chargeback.collectionDate')}>
            <input type="date" value={collectionDate} max={today} onChange={e => setCollectionDate(e.target.value)} />
          </FormField>
        )}
        <FormField label={t('chargeback.amount')}
          error={remaining !== null && value > remaining ? t('chargeback.overRemaining', { amount: fmtAmt(remaining) }) : undefined}>
          <NumericInput unit="₹" min="0" step="0.01" value={amount} onChange={e => setAmount(e.target.value)} data-autofocus />
        </FormField>
        <FormField label={<>{t('record.bankReference')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
          <input type="text" value={reference} maxLength={100} onChange={e => setReference(e.target.value)} />
        </FormField>
      </div>
      <FormField label={t('common:fields.reason')}>
        <input type="text" value={reason} maxLength={500} onChange={e => setReason(e.target.value)} />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
