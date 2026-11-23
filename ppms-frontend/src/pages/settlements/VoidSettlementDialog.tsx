import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { Settlement } from '../../api/paymentSettlementApi'
import { Modal } from '../../components/Modal'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { fmtAmt, fmtDate, typeLabel } from './settlementFormat'

interface VoidSettlementDialogProps {
  pumpId: number
  voidTarget: Settlement
  onClose: () => void
}

export function VoidSettlementDialog({ pumpId, voidTarget, onClose }: VoidSettlementDialogProps) {
  const { t } = useTranslation(['settlements', 'common'])
  const qc           = useQueryClient()
  const { addToast } = useToastStore()
  const [voidReason, setVoidReason] = useState('')
  const [voidError, setVoidError]   = useState<string | null>(null)

  const voidMutation = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) =>
      paymentSettlementApi.voidSettlement(pumpId, id, reason),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['settlements-daily', pumpId] })
      qc.invalidateQueries({ queryKey: ['settlements-wallet', pumpId] })
      onClose()
      addToast(t('void.voided'), 'success')
    },
    onError: (err) => setVoidError(parseApiError(err, t('void.failed'))),
  })

  const handleVoid = (e: React.FormEvent) => {
    e.preventDefault()
    if (!voidReason.trim()) { setVoidError(t('void.reasonRequired')); return }
    voidMutation.mutate({ id: voidTarget.id, reason: voidReason.trim() })
  }

  return (
    <Modal
      title={t('void.title')}
      subtitle={<>{typeLabel(voidTarget.paymentType)} · {fmtDate(voidTarget.settlementDate)} · {fmtAmt(voidTarget.amountReceived)}</>}
      size="lg"
      onClose={onClose}
      closeOnBackdropClick
      onSubmit={handleVoid}
      bodyClassName="flex flex-col gap-5"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="submit" disabled={voidMutation.isPending} className="ui-btn ui-btn-danger">
          {voidMutation.isPending ? <><Spinner /><span>{t('void.voiding')}</span></> : <span>{t('void.title')}</span>}
        </button>
      </>}
    >
      <p className="text-sm text-ink-2">
        {t('void.explanation')}
      </p>
      <div>
        <label className="block">
          <span className="ui-label">{t('void.reason')}</span>
          <input
            type="text"
            className="ui-input"
            placeholder={t('void.reasonPlaceholder')}
            value={voidReason}
            onChange={e => setVoidReason(e.target.value)}
            data-autofocus
          />
        </label>
      </div>

      {voidError && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm py-2">
          {voidError}
        </div>
      )}
    </Modal>
  )
}
