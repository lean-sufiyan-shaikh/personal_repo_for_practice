import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditTransaction } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'
import { formatDecimal } from '../../../utils/format'

// ─── Waive Interest Modal ────────────────────────────────────────────────────

interface WaiveInterestModalProps {
  pumpId: number
  clientId: number
  charge: CreditTransaction
  onWaived: () => void
  onClose: () => void
}

export function WaiveInterestModal({ pumpId, clientId, charge, onWaived, onClose }: WaiveInterestModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.waiveInterestCharge(pumpId, clientId, charge.referenceId, reason.trim()),
    onSuccess: () => {
      onWaived()
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('waiveInterest.failed'))),
  })

  const handleSubmit = () => {
    if (!reason.trim()) { setError(t('waiveInterest.reasonRequired')); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title={t('waiveInterest.title')}
      subtitle={`₹${formatDecimal(charge.amount)} · ${charge.detail}`}
      tone="warning"
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">{t('waiveInterest.keep')}</button>
          <button onClick={handleSubmit} disabled={mutation.isPending} className="ui-btn ui-btn-danger">
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('waiveInterest.waiving')}</span> : t('waiveInterest.title')}
          </button>
        </>
      }
    >
      <p className="text-sm text-ink-2">
        {t('waiveInterest.explanation')}
      </p>
      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">{t('common:fields.reason')} <span aria-hidden="true" className="text-bad">*</span></span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            maxLength={500}
            placeholder={t('waiveInterest.reasonPlaceholder')}
            className="resize-none text-sm"
            data-autofocus
          />
        </label>
      </div>
      {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
    </Modal>
  )
}
