import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Trans, useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Set Credit Limit Modal ──────────────────────────────────────────────────

interface SetCreditLimitModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

export function SetCreditLimitModal({ client, pumpId, onClose }: SetCreditLimitModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const [limit, setLimit] = useState(client.creditLimit > 0 ? String(client.creditLimit) : '')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (creditLimit: number) =>
      creditApi.updateCreditLimit(pumpId, client.id, { creditLimit }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err) => {
      setError(parseApiError(err, t('creditLimit.failed')))
    },
  })

  const handleSubmit = () => {
    const val = limit.trim() === '' ? 0 : parseFloat(limit)
    if (isNaN(val) || val < 0) {
      setError(t('creditLimit.invalid'))
      return
    }
    setError(null)
    mutation.mutate(val)
  }

  return (
    <Modal
      title={t('creditLimit.title')}
      subtitle={client.name}
      tone="info"
      size="lg"
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
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('form.saving')}</span> : t('creditLimit.save')}
          </button>
        </>
      }
    >
      <div className="ui-inline-form">
        <p className="ui-inline-form__copy">
          <Trans t={t} i18nKey="creditLimit.explanation" components={{ strong: <strong /> }} />
        </p>
        <span className="ui-label">{t('creditLimit.label')}</span>
        <div className="relative">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-ink-3 text-sm">₹</span>
          <input aria-label={t('creditLimit.label')}
            type="number"
            min="0"
            step="0.01"
            value={limit}
            onChange={e => setLimit(e.target.value)}
            placeholder={t('creditLimit.placeholder')}
            className="w-full pl-7 pr-3 text-sm"
            data-autofocus
          />
        </div>
      </div>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">
          {error}
        </div>
      )}
    </Modal>
  )
}
