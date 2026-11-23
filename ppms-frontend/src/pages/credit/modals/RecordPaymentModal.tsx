import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient, RecordPaymentRequest } from '../../../api/creditApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { localDateInputValue } from '../../../utils/date'
import { formatNumberIn } from '../../../i18n/format'
import { Spinner } from '../../../components/Spinner'
import { useToastStore } from '../../../store/toastStore'
import { Modal } from '../../../components/Modal'
import { MoneyText } from '../../../components/AmountText'
import { parseApiError } from '../../../utils/apiError'
import { FormGrid } from '../../../components/layout/FormGrid'

interface RecordPaymentModalProps {
  client: CreditClient
  pumpId: number
  outstandingBalanceOverride?: number
  subClients?: CreditClient[]
  onClose: () => void
}

/** Each label is common.json paymentMethods.<mode>. */
const PAYMENT_MODES = ['CASH', 'UPI', 'BANK_TRANSFER', 'OTHER'] as const

export function RecordPaymentModal({ client, pumpId, outstandingBalanceOverride, subClients = [], onClose }: RecordPaymentModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [amount, setAmount] = useState('')
  const [paymentMode, setPaymentMode] = useState<'CASH' | 'UPI' | 'BANK_TRANSFER' | 'OTHER'>('CASH')
  const [paidAt, setPaidAt] = useState(() => localDateInputValue())
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [targetClientId, setTargetClientId] = useState<number>(client.id)

  const targetClient = targetClientId === client.id
    ? client
    : (subClients.find(c => c.id === targetClientId) ?? client)

  const outstandingBalance = targetClientId === client.id
    ? (outstandingBalanceOverride ?? client.outstandingBalance)
    : (targetClient.outstandingBalance ?? 0)

  const mutation = useMutation({
    mutationFn: (req: RecordPaymentRequest) =>
      creditApi.recordPayment(pumpId, targetClientId, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, client.id] })
      if (targetClientId !== client.id) {
        qc.invalidateQueries({ queryKey: ['credit-transactions', pumpId, targetClientId] })
      }
      addToast(t('recordPayment.recorded', { name: targetClient.name }), 'success')
      onClose()
    },
    onError: (err) => {
      const msg = parseApiError(err, t('recordPayment.failed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const handleSubmit = () => {
    const amt = parseFloat(amount)
    if (!amount || isNaN(amt) || amt <= 0) {
      setError(t('recordPayment.invalidAmount'))
      return
    }
    if (amt > outstandingBalance) {
      setError(t('recordPayment.exceedsOutstanding', { amount: `₹${formatNumberIn(outstandingBalance, { minimumFractionDigits: 2 })}` }))
      return
    }
    setError(null)
    mutation.mutate({ amount: amt, paymentMode, paidAt, notes: notes.trim() || undefined })
  }

  return (
    <Modal
      title={t('clientActions.recordPayment')}
      subtitle={client.name}
      tone="success"
      size="lg"
      onClose={onClose}
      bodyClassName="space-y-4"
      footer={
        <>
          <button
            onClick={onClose}
            className="ui-btn ui-btn-secondary"
          >
            {t('common:actions.cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {mutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner />{t('common:actions.saving')}</span>
              : t('clientActions.recordPayment')}
          </button>
        </>
      }
    >
      {subClients.length > 0 && (
        <div className="ui-inline-form">
          <span className="ui-label">{t('recordPayment.recordFor')}</span>
          <SearchableSelect label={t('recordPayment.recordFor')}
            value={String(targetClientId)}
            onChange={v => { setTargetClientId(Number(v)); setAmount(''); setError(null) }}
            options={[
              { value: String(client.id), label: t('recordPayment.parentOption', { name: client.name }) },
              ...subClients.map(c => ({ value: String(c.id), label: c.name })),
            ]}
          />
        </div>
      )}

      {/* Outstanding + Remaining live feedback */}
      <div className="ui-card p-0 overflow-hidden border-warn/30">
        <div className="bg-warn-bg px-4 py-3 flex items-center justify-between">
          <span className="text-sm text-warn">{t('recordPayment.outstandingBalance')}</span>
          <MoneyText value={outstandingBalance} tone="warn" className="text-base font-bold" />
        </div>
        {amount !== '' && (() => {
          const amt = parseFloat(amount) || 0
          const remaining = outstandingBalance - amt
          return (
            <div className={`px-4 py-2.5 flex items-center justify-between border-t ${
              remaining >= 0 ? 'bg-ok-bg border-ok/30' : 'bg-bad-bg border-bad/30'
            }`}>
              <span className={`text-sm ${remaining >= 0 ? 'text-ok' : 'text-bad'}`}>
                {t('recordPayment.remainingAfter')}
              </span>
              <span className={`text-sm font-bold ${remaining >= 0 ? 'text-ok' : 'text-bad'}`}>
                <MoneyText value={Math.abs(remaining)} />
                {remaining < 0 ? t('recordPayment.overSuffix') : ''}
              </span>
            </div>
          )
        })()}
      </div>

      <FormGrid>
        <div className="ui-inline-form">
          <span className="ui-label">
            {t('recordPayment.amount')} <span className="text-bad">*</span>
          </span>
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-3 text-sm">₹</span>
            <input aria-label={t('recordPayment.amount')}
              type="number"
              min="0.01"
              step="0.01"
              value={amount}
              onChange={e => setAmount(e.target.value)}
              placeholder="0.00"
              data-autofocus
              className="pl-7 pr-3"
            />
          </div>
        </div>

        <div className="ui-inline-form">
          <span className="ui-label">
            {t('recordPayment.mode')} <span className="text-bad">*</span>
          </span>
          <SearchableSelect label={t('recordPayment.mode')}
            value={paymentMode}
            onChange={v => setPaymentMode(v as typeof paymentMode)}
            options={PAYMENT_MODES.map(mode => ({ value: mode, label: t(`common:paymentMethods.${mode}`) }))}
          />
        </div>

        <div className="ui-inline-form">
          <label className="block">
            <span className="ui-label">
            {t('recordPayment.date')} <span className="text-bad">*</span>
          </span>
            <input
              type="date"
              value={paidAt}
              onChange={e => setPaidAt(e.target.value)}
            />
          </label>
        </div>

        <div className="ui-inline-form">
          <label className="block">
            <span className="ui-label">{t('common:fields.notes')}</span>
            <input
              type="text"
              value={notes}
              onChange={e => setNotes(e.target.value)}
              placeholder={t('recordPayment.notesPlaceholder')}
            />
          </label>
        </div>
      </FormGrid>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">
          {error}
        </div>
      )}
    </Modal>
  )
}
