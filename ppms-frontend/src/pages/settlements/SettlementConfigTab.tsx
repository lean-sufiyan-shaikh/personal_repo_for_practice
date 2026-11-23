import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { BellOff, BellRing, Check } from 'lucide-react'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import type { SettlementPaymentType, UpdateConfigRequest } from '../../api/paymentSettlementApi'
import { Spinner } from '../../components/Spinner'
import { StatusBadge } from '../../components/StatusBadge'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { TYPE_ACCENT, TYPE_ICONS, TYPE_LABELS } from './settlementFormat'

interface SettlementConfigTabProps {
  pumpId: number
  canConfigure: boolean
}

export function SettlementConfigTab({ pumpId, canConfigure }: SettlementConfigTabProps) {
  const qc           = useQueryClient()
  const { addToast } = useToastStore()

  const { data: configs = [], isLoading: loadingConfigs } = useQuery({
    queryKey: ['settlement-configs', pumpId],
    queryFn:  () => paymentSettlementApi.getConfigs(pumpId),
    enabled:  !!pumpId,
  })

  const [configEdits, setConfigEdits]   = useState<Record<string, UpdateConfigRequest>>({})
  const [savingConfig, setSavingConfig] = useState<string | null>(null)

  const handleConfigChange = <K extends keyof UpdateConfigRequest>(type: SettlementPaymentType, field: K, value: UpdateConfigRequest[K]) => {
    setConfigEdits(prev => ({
      ...prev,
      [type]: { ...(prev[type] ?? { alertTime: '18:00', enabled: true }), [field]: value },
    }))
  }

  const saveConfig = async (type: SettlementPaymentType) => {
    const current = configs.find(c => c.paymentType === type)
    const edit    = configEdits[type]
    if (!edit) return
    setSavingConfig(type)
    try {
      await paymentSettlementApi.upsertConfig(pumpId, type, {
        alertTime: edit.alertTime ?? current?.alertTime ?? '18:00',
        enabled:   edit.enabled  ?? current?.enabled   ?? true,
      })
      qc.invalidateQueries({ queryKey: ['settlement-configs', pumpId] })
      setConfigEdits(prev => { const next = { ...prev }; delete next[type]; return next })
      addToast(`${TYPE_LABELS[type]} alert config saved`, 'success')
    } catch (err) {
      addToast(parseApiError(err, 'Failed to save config'), 'error')
    } finally {
      setSavingConfig(null)
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-3 mb-4">
        Set the time when you want to receive a daily reminder to record each payment type's settlement.
        Alerts arrive as in-app notifications — once per day, after the configured time.
      </p>
      {loadingConfigs ? (
        <div className="flex items-center gap-2 text-ink-3 text-sm"><Spinner /> Loading…</div>
      ) : (
        configs.map(cfg => {
          const edit      = configEdits[cfg.paymentType]
          const alertTime = edit?.alertTime ?? cfg.alertTime
          const enabled   = edit?.enabled   ?? cfg.enabled
          const isDirty   = !!edit
          const accent    = TYPE_ACCENT[cfg.paymentType]

          return (
            <div key={cfg.paymentType} className="ui-dashboard-module px-5 py-4 flex flex-col sm:flex-row sm:items-center gap-4">
              <div className="flex items-center gap-3 flex-1">
                <div className={`w-9 h-9 ${accent.icon} rounded-card flex items-center justify-center text-on-primary text-base shadow-raised flex-shrink-0`}>
                  {TYPE_ICONS[cfg.paymentType]}
                </div>
                <div>
                  <p className="font-semibold text-ink text-sm">{TYPE_LABELS[cfg.paymentType]}</p>
                  <p className="text-xs text-ink-3">Daily reminder at this time (IST)</p>
                </div>
              </div>
              <div className="flex items-center gap-3 flex-wrap sm:flex-nowrap">
                <input aria-label={`${TYPE_LABELS[cfg.paymentType]} reminder time`}
                  type="time"
                  value={alertTime}
                  disabled={!canConfigure}
                  onChange={e => handleConfigChange(cfg.paymentType, 'alertTime', e.target.value)}
                  className="ui-input w-36 text-sm"
                />
                <label className="flex items-center gap-2 text-sm text-ink-2 cursor-pointer select-none whitespace-nowrap">
                  <input
                    type="checkbox"
                    checked={enabled}
                    disabled={!canConfigure}
                    onChange={e => handleConfigChange(cfg.paymentType, 'enabled', e.target.checked)}
                    className="rounded accent-primary w-4 h-4"
                  />
                  Enabled
                </label>
                {canConfigure && isDirty && (
                  <button
                    onClick={() => saveConfig(cfg.paymentType)}
                    disabled={savingConfig === cfg.paymentType}
                    className="ui-btn ui-btn-primary"
                  >
                    {savingConfig === cfg.paymentType
                      ? <><Spinner /><span>Saving…</span></>
                      : <><Check size={14} strokeWidth={2.5} aria-hidden="true" /><span>Save</span></>}
                  </button>
                )}
                {!isDirty && (
                  <StatusBadge tone={enabled ? 'ok' : 'neutral'} icon={enabled ? BellRing : BellOff} label={enabled ? 'Active' : 'Disabled'} />
                )}
              </div>
            </div>
          )
        })
      )}
    </div>
  )
}
