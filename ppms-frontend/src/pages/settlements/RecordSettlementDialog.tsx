import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Landmark, Check } from 'lucide-react'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { PendingSettlementRange, RecordSettlementRequest, SettlementPaymentType, WalletSummary } from '../../api/paymentSettlementApi'
import { daysCoveredBy } from './settlementAging'
import { formatIstDate } from '../../utils/date'
import { Modal } from '../../components/Modal'
import { FormGrid } from '../../components/layout/FormGrid'
import { MoneyText } from '../../components/AmountText'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { localDateInputValue } from '../../utils/date'
import { parseApiError } from '../../utils/apiError'
import { TYPES, TYPE_ACCENT, TYPE_ICONS, fmtAmt, getPending, typeLabel } from './settlementFormat'

interface RecordSettlementDialogProps {
  pumpId: number
  wallet: WalletSummary | undefined
  /** Unsettled collection days, so the dialog can say which days this settlement clears. */
  pendingRanges: PendingSettlementRange[]
  initialType: SettlementPaymentType
  onClose: () => void
}

export function RecordSettlementDialog({ pumpId, wallet, pendingRanges, initialType, onClose }: RecordSettlementDialogProps) {
  const { t } = useTranslation(['settlements', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [formError, setFormError] = useState<string | null>(null)
  const [form, setForm]           = useState<RecordSettlementRequest>({
    paymentType:    initialType,
    settlementDate: localDateInputValue(),
    amountReceived: 0,
    notes:          '',
    bankReference:  '',
  })

  const recordMutation = useMutation({
    mutationFn: (data: RecordSettlementRequest) => paymentSettlementApi.record(pumpId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['settlements-daily', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-wallet', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-pending-age', pumpId] })
      onClose()
      addToast(t('record.recorded'), 'success')
    },
    onError: (err) => {
      const msg = parseApiError(err, t('record.failed'))
      setFormError(msg)
    },
  })

  const handleRecord = (e: React.FormEvent) => {
    e.preventDefault()
    setFormError(null)
    if (!form.settlementDate) { setFormError(t('record.dateRequired')); return }
    if (form.amountReceived <= 0) { setFormError(t('record.amountPositive')); return }
    if (wallet && form.amountReceived > getPending(wallet, form.paymentType)) {
      setFormError(t('record.amountTooHigh', { pending: fmtAmt(getPending(wallet, form.paymentType)), type: typeLabel(form.paymentType) }))
      return
    }
    recordMutation.mutate({ ...form, notes: form.notes || undefined, bankReference: form.bankReference?.trim() || undefined })
  }

  const coveredDays = daysCoveredBy(pendingRanges, form.paymentType, form.amountReceived)

  const pendingAfterRecord = wallet
    ? Math.max(0, getPending(wallet, form.paymentType) - form.amountReceived)
    : null

  const pendingNow = wallet ? getPending(wallet, form.paymentType) : 0

  return (
    <Modal
      title={t('page.record')}
      subtitle={t('record.subtitle')}
      tone="success"
      size="lg"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleRecord}
      bodyClassName="flex flex-col gap-5"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="submit" disabled={recordMutation.isPending} className="ui-btn ui-btn-primary">
          {recordMutation.isPending
            ? <><Spinner /><span>{t('common:actions.saving')}</span></>
            : <><Landmark size={16} strokeWidth={2} aria-hidden="true" /><span>{t('page.record')}</span></>
          }
        </button>
      </>}
    >
            {/* Payment type pill selector */}
            <div>
              <span className="ui-label mb-2">{t('record.paymentType')}</span>
              <div role="group" aria-label={t('record.paymentType')} className="grid grid-cols-3 gap-2">
                {TYPES.map(type => {
                  const accent  = TYPE_ACCENT[type]
                  const active  = form.paymentType === type
                  return (
                    <button
                      key={type}
                      type="button"
                      onClick={() => setForm(f => ({ ...f, paymentType: type }))}
                      className={`flex flex-col items-center gap-1.5 py-3 px-2 rounded-card border-2 font-semibold text-sm transition-all ${
                        active ? accent.pillActive : accent.pill + ' bg-surface'
                      }`}
                    >
                      <span className="leading-none">{TYPE_ICONS[type]}</span>
                      <span>{typeLabel(type)}</span>
                    </button>
                  )
                })}
              </div>
            </div>

            {/* Current pending context for selected type */}
            {wallet && (
              <div className={`rounded-card border px-4 py-3 ${
                pendingNow > 0 ? 'bg-warn-bg border-warn' : 'bg-ok-bg border-ok'
              }`}>
                <div className="flex items-center justify-between">
                  <span className="text-xs font-semibold text-ink-3 uppercase tracking-wide">
                    {t('record.currentPending', { type: typeLabel(form.paymentType) })}
                  </span>
                  <MoneyText value={pendingNow} tone={pendingNow > 0 ? 'warn' : 'ok'} className="text-base font-bold" />
                </div>
                {form.amountReceived > 0 && pendingAfterRecord !== null && (
                  <div className="flex items-center justify-between mt-1.5 pt-1.5 border-t border-dashed border-border">
                    <span className="text-xs text-ink-3">{t('record.afterThis')}</span>
                    <span className={`text-sm font-bold tabular-nums ${pendingAfterRecord > 0 ? 'text-warn' : 'text-ok'}`}>
                      <MoneyText value={pendingAfterRecord} />
                      {pendingAfterRecord <= 0 && <span className="ml-1 inline-flex items-center gap-1 text-xs"><Check size={11} strokeWidth={2.5} aria-hidden="true" />{t('wallet.fullySettled')}</span>}
                    </span>
                  </div>
                )}
              </div>
            )}

            <FormGrid>
              <div>
                <span className="ui-label">{t('record.date')}</span>
                <p className="text-xs text-ink-3 mb-1.5 -mt-1">{t('record.dateHint')}</p>
                <input aria-label={t('record.date')}
                  type="date"
                  className="ui-input"
                  value={form.settlementDate}
                  max={localDateInputValue()}
                  onChange={e => setForm(f => ({ ...f, settlementDate: e.target.value }))}
                  required
                />
              </div>
              <div>
                <span className="ui-label">{t('record.amount')}</span>
                <p className="text-xs text-ink-3 mb-1.5 -mt-1">{t('record.amountHint')}</p>
                <div className="relative">
                  <span className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-3 font-semibold text-sm pointer-events-none">₹</span>
                  <input aria-label={t('record.amount')}
                    type="number"
                    className="ui-input pl-7"
                    min={0}
                    step={0.01}
                    placeholder="0.00"
                    value={form.amountReceived || ''}
                    onChange={e => setForm(f => ({ ...f, amountReceived: parseFloat(e.target.value) || 0 }))}
                    required
                  />
                </div>
              </div>

              <div>
                <label className="block">
                  <span className="ui-label">{t('record.bankReference')} <span className="text-ink-3 font-normal">{t('common:fields.optionalSuffix')}</span></span>
                  <input
                    type="text"
                    className="ui-input"
                    maxLength={100}
                    placeholder={t('record.bankReferencePlaceholder')}
                    value={form.bankReference ?? ''}
                    onChange={e => setForm(f => ({ ...f, bankReference: e.target.value }))}
                  />
                </label>
                <p className="ui-help">{t('record.bankReferenceHelp')}</p>
              </div>

              <div>
                <label className="block">
                  <span className="ui-label">{t('common:fields.notes')} <span className="text-ink-3 font-normal">{t('common:fields.optionalSuffix')}</span></span>
                  <input
                    type="text"
                    className="ui-input"
                    placeholder={t('record.notesPlaceholder')}
                    value={form.notes ?? ''}
                    onChange={e => setForm(f => ({ ...f, notes: e.target.value }))}
                  />
                </label>
              </div>
            </FormGrid>

            {coveredDays.length > 0 && (
              <div className="ui-card ui-card-muted px-4 py-3" aria-live="polite">
                <p className="text-xs font-semibold text-ink-2">{t('record.clears', { type: typeLabel(form.paymentType) })}</p>
                <ul className="mt-1 space-y-0.5 text-xs text-ink-2">
                  {coveredDays.map((day) => (
                    <li key={day.date} className="flex justify-between gap-3">
                      <span>{formatIstDate(day.date, { day: 'numeric', month: 'short' })}</span>
                      <span className="tabular-nums">
                        {day.covered < day.unsettled
                          ? t('record.partOf', { covered: fmtAmt(day.covered), unsettled: fmtAmt(day.unsettled) })
                          : fmtAmt(day.covered)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {formError && (
              <div role="alert" className="ui-alert ui-alert-danger text-sm py-2">
                {formError}
              </div>
            )}
    </Modal>
  )
}
