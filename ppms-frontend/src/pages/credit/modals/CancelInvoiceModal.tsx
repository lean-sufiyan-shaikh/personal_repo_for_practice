import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditInvoice } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'
import { useToastStore } from '../../../store/toastStore'

// ─── Cancel Invoice Modal ────────────────────────────────────────────────────

interface CancelInvoiceModalProps {
  invoice: CreditInvoice
  pumpId: number
  onClose: () => void
}

export function CancelInvoiceModal({ invoice, pumpId, onClose }: CancelInvoiceModalProps) {
  const { t } = useTranslation('credit')
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.cancelInvoice(pumpId, invoice.id, reason.trim()),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-invoices', pumpId] })
      addToast(t('cancelInvoice.cancelled', { number: invoice.invoiceNumber }), 'success')
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('cancelInvoice.failed'))),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError(t('cancelInvoice.reasonRequired')); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('cancelInvoice.title')}
      subtitle={invoice.invoiceNumber}
      tone="warning"
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">{t('cancelInvoice.keep')}</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('cancelInvoice.cancelling')}</span> : t('cancelInvoice.title')}
          </button>
        </>
      }
    >
      <p className="text-sm text-ink-2">
        {t('cancelInvoice.explanation')}
      </p>
      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">{t('form.reason')} <span aria-hidden="true" className="text-bad">*</span></span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            placeholder={t('cancelInvoice.reasonPlaceholder')}
            className="resize-none text-sm"
            data-autofocus
          />
        </label>
      </div>
      {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
    </Modal>
  )
}
