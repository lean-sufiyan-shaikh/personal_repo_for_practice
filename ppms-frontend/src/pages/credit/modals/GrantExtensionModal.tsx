import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient, CreateExtensionRequest, CreditExtensionType } from '../../../api/creditApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { localDateInputValue } from '../../../utils/date'
import { formatNumberIn } from '../../../i18n/format'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Grant Extension Modal ───────────────────────────────────────────────────

interface GrantExtensionModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

/** Each label is credit.json grantExtension.types.<type>. */
const EXTENSION_TYPES = ['AMOUNT_EXTENSION', 'BILLING_CYCLE_EXTENSION', 'OVERDUE_BLOCK_WAIVER'] as const

export function GrantExtensionModal({ client, pumpId, onClose }: GrantExtensionModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const [extensionType, setExtensionType] = useState<CreditExtensionType>('BILLING_CYCLE_EXTENSION')
  const qc = useQueryClient()
  const [extensionAmount, setExtensionAmount] = useState('')
  const [expiryDate, setExpiryDate] = useState(() => localDateInputValue())
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  // Minimum expiry = tomorrow
  const minDate = localDateInputValue(1)

  const mutation = useMutation({
    mutationFn: (req: CreateExtensionRequest) =>
      creditApi.createExtension(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['active-extensions', pumpId, client.id] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('grantExtension.failed'))),
  })

  const handleSubmit = () => {
    if (!expiryDate) { setError(t('grantExtension.expiryRequired')); return }
    if (!reason.trim()) { setError(t('grantExtension.reasonRequired')); return }
    if (extensionType === 'AMOUNT_EXTENSION') {
      const amt = parseFloat(extensionAmount)
      if (!extensionAmount || isNaN(amt) || amt <= 0) { setError(t('grantExtension.amountRequired')); return }
    }
    setError(null)
    mutation.mutate({
      extensionType,
      extensionAmount: extensionType === 'AMOUNT_EXTENSION' ? parseFloat(extensionAmount) : undefined,
      expiryDate,
      reason: reason.trim(),
    })
  }

  return (
    <Modal
      title={t('grantExtension.title')}
      subtitle={client.name}
      tone="warning"
      onClose={onClose}
      bodyClassName="space-y-4"
      footer={
        <>
          <button onClick={onClose} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('grantExtension.granting')}</span> : t('clientActions.grantExtension')}
          </button>
        </>
      }
    >
      <div className="ui-inline-form">
        <span className="ui-label">{t('grantExtension.typeLabel')}</span>
        <SearchableSelect label={t('grantExtension.typeLabel')}
          value={extensionType}
          onChange={(v) => setExtensionType(v as CreditExtensionType)}
          options={EXTENSION_TYPES.map(type => ({ value: type, label: t(`grantExtension.types.${type}`) }))}
        />
      </div>

      {extensionType === 'AMOUNT_EXTENSION' && (
        <div className="ui-inline-form">
          <label className="block">
            <span className="ui-label">
            {t('grantExtension.amountLabel')} <span className="text-bad">*</span>
          </span>
            <input
              type="number" min="1" step="0.01"
              value={extensionAmount}
              onChange={(e) => setExtensionAmount(e.target.value)}
              placeholder={t('grantExtension.amountPlaceholder')}
              className="text-sm"
            />
          </label>
          <p className="text-xs text-ink-3 mt-1">
            {t('grantExtension.currentLimit', {
              limit: client.creditLimit > 0 ? `₹${formatNumberIn(client.creditLimit)}` : t('grantExtension.unlimited'),
            })}
          </p>
        </div>
      )}

      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">
          {t('grantExtension.expiresOn')} <span className="text-bad">*</span>
        </span>
          <input
            type="date" min={minDate}
            value={expiryDate}
            onChange={(e) => setExpiryDate(e.target.value)}
            className="text-sm"
          />
        </label>
      </div>

      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">
          {t('grantExtension.reasonLabel')} <span className="text-bad">*</span>
        </span>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder={t('grantExtension.reasonPlaceholder')}
            className="resize-none text-sm"
            data-autofocus
          />
        </label>
      </div>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
      )}
    </Modal>
  )
}
