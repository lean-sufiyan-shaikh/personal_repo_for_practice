import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Trans, useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient, UpdateInterestSettingsRequest } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'

// ─── Set Interest Settings Modal ─────────────────────────────────────────────

interface SetInterestSettingsModalProps {
  client: CreditClient
  pumpId: number
  onClose: () => void
}

export function SetInterestSettingsModal({ client, pumpId, onClose }: SetInterestSettingsModalProps) {
  const { t } = useTranslation(['credit', 'common'])
  const qc = useQueryClient()
  const [rate, setRate] = useState(client.monthlyInterestRate > 0 ? String(client.monthlyInterestRate) : '')
  const [graceDays, setGraceDays] = useState(String(client.interestGraceDays ?? 1))
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (req: UpdateInterestSettingsRequest) =>
      creditApi.updateInterestSettings(pumpId, client.id, req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['credit-ledger', pumpId] })
      onClose()
    },
    onError: (err) => setError(parseApiError(err, t('interestSettings.failed'))),
  })

  const handleSubmit = () => {
    const r = rate.trim() === '' ? 0 : parseFloat(rate)
    const g = parseInt(graceDays, 10)
    if (isNaN(r) || r < 0 || r > 100) { setError(t('interestSettings.invalidRate')); return }
    if (isNaN(g) || g < 0) { setError(t('interestSettings.invalidGraceDays')); return }
    setError(null)
    mutation.mutate({ monthlyInterestRate: r, interestGraceDays: g })
  }

  return (
    <Modal
      title={t('interestSettings.title')}
      subtitle={client.name}
      tone="warning"
      size="sm"
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
            {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('form.saving')}</span> : t('interestSettings.save')}
          </button>
        </>
      }
    >
      <div className="ui-inline-form">
        <p className="ui-inline-form__copy">
          <Trans t={t} i18nKey="interestSettings.explanation" components={{ strong: <strong /> }} />
        </p>

        <span className="ui-label">{t('interestSettings.rateLabel')}</span>
        <div className="relative">
          <input aria-label={t('interestSettings.rateLabel')}
            type="number" min="0" max="100" step="0.01"
            value={rate}
            onChange={e => setRate(e.target.value)}
            placeholder={t('interestSettings.ratePlaceholder')}
            className="w-full pr-8 pl-3 text-sm"
            data-autofocus
          />
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-ink-3 text-sm">%</span>
        </div>
        <p className="text-xs text-ink-3 mt-1">
          {t('interestSettings.rateExample')}
        </p>
      </div>

      <div className="ui-inline-form">
        <label className="block">
          <span className="ui-label">{t('interestSettings.graceLabel')}</span>
          <input
            type="number" min="0" step="1"
            value={graceDays}
            onChange={e => setGraceDays(e.target.value)}
            className="text-sm"
          />
        </label>
        <p className="text-xs text-ink-3 mt-1">
          {t('interestSettings.graceHelp')}
        </p>
      </div>

      {error && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>
      )}
    </Modal>
  )
}
