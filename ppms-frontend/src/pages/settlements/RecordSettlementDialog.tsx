import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Check } from 'lucide-react'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { PendingSettlementRange, RecordSettlementRequest, SettlementAmountBasis, SettlementPaymentType, WalletSummary } from '../../api/paymentSettlementApi'
import { daysCoveredBy } from './settlementAging'
import { Modal } from '../../components/Modal'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'
import { FormField } from '../../components/FormField'
import { MoneyText } from '../../components/AmountText'
import { NumericInput } from '../../components/NumericInput'
import { LiveFigures } from '../../components/LiveFigures'
import { LoadingButton } from '../../components/LoadingButton'
import { StillNeeded } from '../../components/StillNeeded'
import { useToastStore } from '../../store/toastStore'
import { localDateInputValue } from '../../utils/date'
import { roundToPaise } from '../../utils/money'
import { parseApiError } from '../../utils/apiError'
import { fmtAmt, getPending, typeLabel } from './settlementFormat'
import { PaymentTypePicker } from './PaymentTypePicker'
import { CoveredDaysList } from './CoveredDaysList'
import { SettlementChargesFields } from './SettlementChargesFields'
import type { ChargeOverrides } from './SettlementChargesFields'
import { useSettlementCharges } from './useSettlementCharges'

interface RecordSettlementDialogProps {
  pumpId: number
  wallet: WalletSummary | undefined
  /** Unsettled collection days, so the dialog can say which days this settlement clears. */
  pendingRanges: PendingSettlementRange[]
  initialType: SettlementPaymentType
  onClose: () => void
}

interface FieldErrors {
  date?: string
  amount?: string
}

const MIN_AMOUNT = 0.01

function typedAmount(text: string): number {
  return roundToPaise(parseFloat(text) || 0)
}

export function RecordSettlementDialog({ pumpId, wallet, pendingRanges, initialType, onClose }: RecordSettlementDialogProps) {
  const { t } = useTranslation(['settlements', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [serverError, setServerError] = useState<string | null>(null)
  const [paymentType, setPaymentType] = useState<SettlementPaymentType>(initialType)
  const [settlementDate, setSettlementDate] = useState(localDateInputValue())
  const [amountText, setAmountText] = useState('')
  const [basis, setBasis] = useState<SettlementAmountBasis>('NET')
  const [overrides, setOverrides] = useState<ChargeOverrides>({ editing: false, mdrAmount: '', gstOnMdrAmount: '' })
  const [notes, setNotes] = useState('')
  const [bankReference, setBankReference] = useState('')

  const amount = typedAmount(amountText)
  const overriddenCharges = overrides.editing
    ? { mdrAmount: typedAmount(overrides.mdrAmount), gstOnMdrAmount: typedAmount(overrides.gstOnMdrAmount) }
    : {}
  const { charges, error: chargesError } = useSettlementCharges(pumpId, { paymentType, amount, amountBasis: basis, ...overriddenCharges })

  const recordMutation = useMutation({
    mutationFn: (data: RecordSettlementRequest) => paymentSettlementApi.record(pumpId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['settlements-daily', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-wallet', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-pending-age', pumpId] })
      onClose()
      addToast(t('record.recorded'), 'success')
    },
    onError: (err) => setServerError(parseApiError(err, t('record.failed'))),
  })

  const hasAmount = amount >= MIN_AMOUNT
  // Collections are cleared by the gross amount; until the server answers, the net amount typed is the least it can be.
  const grossAmount = basis === 'GROSS' ? amount : charges?.grossAmount ?? amount
  const pendingNow = wallet ? getPending(wallet, paymentType) : 0
  const exceedsPending = wallet !== undefined && grossAmount > pendingNow
  const tooHighMessage = t('record.amountTooHigh', { pending: fmtAmt(pendingNow), type: typeLabel(paymentType) })
  const pendingAfterRecord = Math.max(0, pendingNow - grossAmount)

  const handleRecord = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    const errors: FieldErrors = {}
    if (!settlementDate) errors.date = t('record.dateRequired')
    if (!hasAmount) errors.amount = t('record.amountPositive')
    else if (exceedsPending) errors.amount = tooHighMessage
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return
    recordMutation.mutate({
      paymentType,
      settlementDate,
      amountReceived: amount,
      amountBasis: basis,
      ...overriddenCharges,
      notes: notes.trim() || undefined,
      bankReference: bankReference.trim() || undefined,
    })
  }

  const stillNeeded = [
    ...(settlementDate ? [] : [t('record.needDate')]),
    ...(hasAmount ? [] : [t('record.needAmount')]),
  ]

  const optional = (label: string) => (
    <>{label} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>
  )

  return (
    <Modal
      tone="info"
      title={t('record.title')}
      subtitle={t('record.subtitle')}
      size="lg"
      onClose={onClose}
      canClose={!recordMutation.isPending}
      closeOnBackdropClick
      fullScreenOnPhone
      onSubmit={handleRecord}
      bodyClassName="flex flex-col gap-5"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={recordMutation.isPending} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="submit" isLoading={recordMutation.isPending} loadingLabel={t('common:actions.saving')}
          disabled={stillNeeded.length > 0}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          className="ui-btn-primary">
          {t('record.title')}
        </LoadingButton>
      </>}
    >
      <PaymentTypePicker value={paymentType} onChange={setPaymentType} />

      <FormGrid>
        <FormField label={t('record.date')} error={fieldErrors.date} help={t('record.dateHint')}>
          <input type="date" value={settlementDate} max={localDateInputValue()} onChange={e => setSettlementDate(e.target.value)} required />
        </FormField>
        <FormField label={t('record.amount')} error={fieldErrors.amount ?? (exceedsPending ? tooHighMessage : undefined)}
          help={basis === 'NET' ? t('charges.amountHelpNet') : t('charges.amountHelpGross')}>
          <NumericInput unit="₹" min={MIN_AMOUNT} step={0.01} data-autofocus value={amountText}
            onChange={e => setAmountText(e.target.value)} required />
        </FormField>

        <FormGridFullRow>
          <SettlementChargesFields
            basis={basis}
            onBasisChange={setBasis}
            charges={charges}
            chargesError={chargesError ? parseApiError(chargesError, t('charges.loadFailed')) : null}
            overrides={overrides}
            onOverridesChange={setOverrides}
          />
        </FormGridFullRow>

        <FormField label={optional(t('record.bankReference'))} help={t('record.bankReferenceHelp')}>
          <input type="text" maxLength={100} value={bankReference} onChange={e => setBankReference(e.target.value)} />
        </FormField>

        <FormField label={optional(t('common:fields.notes'))}>
          <input type="text" value={notes} onChange={e => setNotes(e.target.value)} />
        </FormField>

        {wallet && (
          <FormGridFullRow>
            <LiveFigures
              label={t('record.afterThis')}
              figures={[
                { label: t('record.currentPending', { type: typeLabel(paymentType) }), value: <MoneyText value={pendingNow} /> },
                { label: t('record.thisSettlement'), value: <MoneyText value={hasAmount ? grossAmount : null} /> },
                {
                  label: t('record.afterThis'),
                  value: !hasAmount ? <MoneyText value={null} />
                    : pendingAfterRecord > 0 ? <MoneyText value={pendingAfterRecord} />
                    : (
                      <span className="inline-flex items-center gap-1 text-ok">
                        <Check size={14} strokeWidth={2.5} aria-hidden="true" />{t('wallet.fullySettled')}
                      </span>
                    ),
                  isResult: true,
                },
              ]}
            />
          </FormGridFullRow>
        )}
      </FormGrid>

      <CoveredDaysList type={paymentType} days={daysCoveredBy(pendingRanges, paymentType, hasAmount ? grossAmount : 0)} />

      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
