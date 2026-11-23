import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary } from '../../api/pumpApi'
import { useToastStore } from '../../store/toastStore'

// ── Pump Settings Content ─────────────────────────────────────────────────────

export function PumpSettingsContent({ pump, onUpdated }: { pump: PumpSummary; onUpdated: () => void }) {
  const { t } = useTranslation(['setup', 'common'])
  const { addToast } = useToastStore()
  const [threshold, setThreshold] = useState(pump.discrepancyEscalationThreshold?.toString() ?? '')
  const [error, setError]         = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (val: number | null) =>
      pumpApi.updatePumpSettings(pump.id, { discrepancyEscalationThreshold: val }),
    onSuccess: () => {
      addToast(t('settings.saved'), 'success')
      setError(null)
      onUpdated()
    },
    onError: (err) => setError(parseApiError(err, t('settings.saveFailed'))),
  })

  const handleSave = () => {
    const val = threshold.trim()
    if (val === '') {
      mutation.mutate(null)
      return
    }
    const num = parseFloat(val)
    if (isNaN(num) || num < 0) {
      setError(t('settings.invalidAmount'))
      return
    }
    mutation.mutate(num)
  }

  return (
    <div className="space-y-5 pt-2">
      <div className="ui-alert bg-warn-bg border border-warn rounded-control p-3 text-xs text-warn-strong">
        {t('settings.explanation')}
      </div>

      <div className="max-w-xs">
        <label className="block">
          <span className="ui-label">{t('settings.thresholdLabel')}</span>
          <input
            type="number"
            min="0"
            step="1"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
            placeholder={t('settings.thresholdPlaceholder')}
            className="text-sm"
          />
        </label>
        {error && <p role="alert" className="ui-error-text mt-1">{error}</p>}
      </div>

      <button
        onClick={handleSave}
        disabled={mutation.isPending}
        className="ui-btn ui-btn-primary"
      >
        {mutation.isPending ? t('common:actions.saving') : t('settings.save')}
      </button>
    </div>
  )
}
