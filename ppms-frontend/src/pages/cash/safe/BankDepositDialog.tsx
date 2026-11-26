import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { RecordBankDepositRequest } from '../../../api/cashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { FormGrid } from '../../../components/layout/FormGrid'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { MoneyText } from '../../../components/AmountText'
import { NumericInput } from '../../../components/NumericInput'
import { StillNeeded } from '../../../components/StillNeeded'
import { parseApiError } from '../../../utils/apiError'
import { attemptFor } from '../../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../../utils/idempotencyKey'
import { localDateInputValue } from '../../../utils/date'
import { roundToPaise } from '../../../utils/money'
import { formatCurrency } from '../../../utils/format'
import { useToastStore } from '../../../store/toastStore'
import { SLIP_ACCEPT, slipFileProblem } from './slipFile'

interface Props {
  pumpId: number
  balance: number
  onClose: () => void
}

/** Records cash taken from the safe to the bank against its slip, with an optional photo or PDF of the slip. */
export function BankDepositDialog({ pumpId, balance, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [amount, setAmount] = useState('')
  const [bankAccount, setBankAccount] = useState('')
  const [depositDate, setDepositDate] = useState(() => localDateInputValue())
  const [slipReference, setSlipReference] = useState('')
  const [notes, setNotes] = useState('')
  const [slipFile, setSlipFile] = useState<File | null>(null)
  const [fileProblem, setFileProblem] = useState<string | null>(null)
  const [submitAttempt, setSubmitAttempt] = useState<SubmitAttempt | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = () => {
    for (const key of ['safe', 'safeLedger', 'bankDeposits', 'cashEvents']) {
      queryClient.invalidateQueries({ queryKey: [key, pumpId] })
    }
  }

  const mutation = useMutation({
    mutationFn: async ({ request, idempotencyKey }: { request: RecordBankDepositRequest; idempotencyKey: string }) => {
      const deposit = await cashApi.recordDeposit(pumpId, request, idempotencyKey)
      if (!slipFile) return { deposit, slipError: null }
      try {
        await cashApi.attachDepositSlip(pumpId, deposit.id, slipFile)
        return { deposit, slipError: null }
      } catch (err) {
        return { deposit, slipError: parseApiError(err, t('safe.deposits.attachFailed')) }
      }
    },
    onSuccess: ({ deposit, slipError }) => {
      refresh()
      addToast(t('safe.deposit.saved', { amount: formatCurrency(deposit.amount) }), 'success')
      if (slipError) addToast(t('safe.deposit.slipUploadFailed', { reason: slipError }), 'warning')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('safe.deposit.failed'))),
  })

  const amountValue = Number(amount)
  const hasAmount = amount.trim() !== '' && Number.isFinite(amountValue) && amountValue > 0
  const after = hasAmount ? roundToPaise(balance - amountValue) : null
  const exceedsBalance = after !== null && after < 0
  const stillNeeded = [
    !hasAmount && t('safe.deposit.needAmount'),
    bankAccount.trim() === '' && t('safe.deposit.needBank'),
    depositDate === '' && t('safe.deposit.needDate'),
    slipReference.trim() === '' && t('safe.deposit.needSlip'),
  ].filter((item): item is string => typeof item === 'string')
  const canSave = stillNeeded.length === 0 && !exceedsBalance && fileProblem === null

  const chooseFile = (file: File | undefined) => {
    setSlipFile(file ?? null)
    setFileProblem(file ? slipFileProblem(file) : null)
  }

  const submit = () => {
    if (!canSave) return
    setError(null)
    const request: RecordBankDepositRequest = {
      amount: roundToPaise(amountValue),
      bankAccount: bankAccount.trim(),
      depositDate,
      slipReference: slipReference.trim(),
      notes: notes.trim() || undefined,
    }
    const attempt = attemptFor(submitAttempt, request)
    setSubmitAttempt(attempt)
    mutation.mutate({ request, idempotencyKey: attempt.key })
  }

  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  return (
    <Modal
      title={t('safe.deposit.title')}
      subtitle={t('safe.deposit.subtitle')}
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
          <LoadingButton type="button" onClick={submit} disabled={!canSave} isLoading={mutation.isPending}
            aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
            loadingLabel={t('safe.deposit.saving')} className="ui-btn-primary min-h-11">
            {t('safe.deposit.save')}
          </LoadingButton>
        </>
      }
    >
      <FormGrid>
        <FormField label={t('safe.deposit.amount')}
          error={exceedsBalance ? t('safe.deposit.exceeds', { amount: formatCurrency(balance) }) : undefined}>
          <NumericInput unit="₹" min="0" step="0.01" value={amount} onChange={(e) => setAmount(e.target.value)} />
        </FormField>
        <FormField label={t('safe.deposit.date')}>
          <input type="date" value={depositDate} max={localDateInputValue()} onChange={(e) => setDepositDate(e.target.value)} />
        </FormField>
        <FormField label={t('safe.deposit.bankAccount')}>
          <input type="text" value={bankAccount} maxLength={100} placeholder={t('safe.deposit.bankAccountPlaceholder')}
            onChange={(e) => setBankAccount(e.target.value)} />
        </FormField>
        <FormField label={t('safe.deposit.slipReference')}>
          <input type="text" value={slipReference} maxLength={100} onChange={(e) => setSlipReference(e.target.value)} />
        </FormField>
      </FormGrid>

      <LiveFigures
        label={t('safe.deposit.figuresLabel')}
        figures={[
          { label: t('safe.deposit.inSafe'), value: <MoneyText value={balance} /> },
          { label: t('safe.deposit.afterDeposit'), value: <MoneyText value={after} showSign={exceedsBalance} tone={exceedsBalance ? 'bad' : undefined} />, isResult: true },
        ]}
      />

      <FormField label={<>{t('safe.deposit.slipFile')} {optional}</>} help={t('safe.deposit.slipHint')} error={fileProblem ?? undefined}>
        <input type="file" accept={SLIP_ACCEPT} onChange={(e) => chooseFile(e.target.files?.[0])} />
      </FormField>
      <FormField label={<>{t('safe.deposit.notes')} {optional}</>}>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>

      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
