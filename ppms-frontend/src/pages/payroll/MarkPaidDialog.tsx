import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { payrollApi } from '../../api/payrollApi'
import type { PayrollPaymentMode, PayrollRecord } from '../../api/payrollApi'
import { Modal } from '../../components/Modal'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { LiveFigures } from '../../components/LiveFigures'
import { MoneyText } from '../../components/AmountText'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { istDateString } from '../../utils/date'
import { fmtDate } from './payrollFormat'

const PAYROLL_PAYMENT_MODES: PayrollPaymentMode[] = ['CASH', 'BANK', 'UPI']

interface MarkPaidDialogProps {
  pumpId: number
  record: PayrollRecord
  staffName: string
  onClose: () => void
}

/** Records when and how an approved payroll was paid; a cash payment comes out of the safe. */
export function MarkPaidDialog({ pumpId, record, staffName, onClose }: MarkPaidDialogProps) {
  const { t } = useTranslation(['payroll', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const today = istDateString()
  const [paidDate, setPaidDate] = useState(today)
  const [paymentMode, setPaymentMode] = useState<PayrollPaymentMode>('BANK')
  const [reference, setReference] = useState('')
  const [dateError, setDateError] = useState<string | undefined>()
  const [serverError, setServerError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => payrollApi.markPaid(pumpId, record.id, {
      paidDate, paymentMode, paymentReference: reference.trim() || undefined,
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['payroll', pumpId] })
      addToast(t('records.statusUpdated.PAID'), 'success')
      onClose()
    },
    onError: (err) => setServerError(parseApiError(err, t('markPaid.failed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    if (paidDate === '' || paidDate > today) { setDateError(t('markPaid.invalidDate')); return }
    setDateError(undefined)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('markPaid.title')}
      subtitle={`${staffName} · ${fmtDate(record.periodFrom)} – ${fmtDate(record.periodTo)}`}
      tone="info"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={handleSubmit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('markPaid.saving')} className="ui-btn-primary">
            {t('markPaid.save')}
          </LoadingButton>
        </>
      }
    >
      <LiveFigures figures={[{ label: t('markPaid.netPay'), value: <MoneyText value={record.netPay} />, isResult: true }]} />
      <FormField label={t('markPaid.paidDate')} error={dateError}>
        <input type="date" value={paidDate} max={today} data-autofocus
          onChange={(e) => { setPaidDate(e.target.value); setDateError(undefined) }} />
      </FormField>
      <fieldset>
        <legend className="ui-label">{t('markPaid.mode')}</legend>
        <div className="flex flex-wrap gap-x-4 gap-y-1">
          {PAYROLL_PAYMENT_MODES.map((option) => (
            <label key={option} className="flex min-h-10 items-center gap-2 text-sm text-ink">
              <input type="radio" name="payroll-payment-mode" value={option} checked={paymentMode === option}
                onChange={() => setPaymentMode(option)} />
              {t(`markPaid.modes.${option}`)}
            </label>
          ))}
        </div>
      </fieldset>
      <FormField label={<>{t('markPaid.reference')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <input type="text" value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} />
      </FormField>
      {paymentMode === 'CASH' && <p className="text-sm text-ink-2">{t('markPaid.cashHint')}</p>}
      <p className="text-sm text-ink-3">{t('markPaid.finalHint')}</p>
      {serverError && <p role="alert" className="ui-alert ui-alert-danger">{serverError}</p>}
    </Modal>
  )
}
