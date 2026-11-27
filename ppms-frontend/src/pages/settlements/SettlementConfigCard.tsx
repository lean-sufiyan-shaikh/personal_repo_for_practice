import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BellOff, BellRing, Check } from 'lucide-react'
import type { SettlementConfig, UpdateConfigRequest } from '../../api/paymentSettlementApi'
import { FormField } from '../../components/FormField'
import { NumericInput } from '../../components/NumericInput'
import { Spinner } from '../../components/Spinner'
import { StatusBadge } from '../../components/StatusBadge'
import { TYPE_ACCENT, TYPE_ICONS, typeLabel } from './settlementFormat'

const MAX_MDR_PERCENT = 10
const MAX_GST_PERCENT = 100

interface SettlementConfigCardProps {
  config: SettlementConfig
  canConfigure: boolean
  isSaving: boolean
  /** Resolves true once saved, so the edits are kept when saving fails. */
  onSave: (request: UpdateConfigRequest) => Promise<boolean>
}

interface ConfigDraft {
  alertTime: string
  enabled: boolean
  mdrPercent: string
  gstOnMdrPercent: string
}

function draftOf(config: SettlementConfig): ConfigDraft {
  return {
    alertTime: config.alertTime,
    enabled: config.enabled,
    mdrPercent: String(config.mdrPercent),
    gstOnMdrPercent: String(config.gstOnMdrPercent),
  }
}

function percentProblem(text: string, max: number): boolean {
  const value = Number(text)
  return text.trim() === '' || !Number.isFinite(value) || value < 0 || value > max
}

/** One payment type's daily reminder and the bank's MDR and GST-on-MDR rates used to split settlements into gross and net. */
export function SettlementConfigCard({ config, canConfigure, isSaving, onSave }: SettlementConfigCardProps) {
  const { t } = useTranslation(['settlements', 'common'])
  const [draft, setDraft] = useState<ConfigDraft | null>(null)
  const shown = draft ?? draftOf(config)
  const isDirty = draft !== null
  const mdrInvalid = percentProblem(shown.mdrPercent, MAX_MDR_PERCENT)
  const gstInvalid = percentProblem(shown.gstOnMdrPercent, MAX_GST_PERCENT)
  const accent = TYPE_ACCENT[config.paymentType]
  const type = typeLabel(config.paymentType)

  const change = (changes: Partial<ConfigDraft>) => setDraft({ ...shown, ...changes })

  const save = async () => {
    if (mdrInvalid || gstInvalid) return
    const saved = await onSave({
      alertTime: shown.alertTime,
      enabled: shown.enabled,
      mdrPercent: Number(shown.mdrPercent),
      gstOnMdrPercent: Number(shown.gstOnMdrPercent),
    })
    if (saved) setDraft(null)
  }

  return (
    <div className="ui-dashboard-module px-5 py-4 flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <div className={`w-9 h-9 ${accent.icon} rounded-card flex items-center justify-center text-on-primary text-base shadow-raised flex-shrink-0`}>
          {TYPE_ICONS[config.paymentType]}
        </div>
        <div className="flex-1">
          <p className="font-semibold text-ink text-sm">{type}</p>
          <p className="text-xs text-ink-3">{t('alerts.dailyReminder')}</p>
        </div>
        {!isDirty && (
          <StatusBadge tone={shown.enabled ? 'ok' : 'neutral'} icon={shown.enabled ? BellRing : BellOff}
            label={shown.enabled ? t('alerts.active') : t('alerts.disabled')} />
        )}
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4 lg:items-end">
        <FormField label={t('alerts.reminderTime', { type })}>
          <input type="time" value={shown.alertTime} disabled={!canConfigure}
            onChange={e => change({ alertTime: e.target.value })} className="text-sm" />
        </FormField>
        <FormField label={t('charges.mdrPercent', { type })} help={t('charges.mdrPercentHelp')}
          error={canConfigure && mdrInvalid ? t('charges.mdrPercentRange') : undefined}>
          <NumericInput unit="%" min={0} max={MAX_MDR_PERCENT} step={0.01} value={shown.mdrPercent} disabled={!canConfigure}
            onChange={e => change({ mdrPercent: e.target.value })} />
        </FormField>
        <FormField label={t('charges.gstPercent', { type })} help={t('charges.gstPercentHelp')}
          error={canConfigure && gstInvalid ? t('charges.gstPercentRange') : undefined}>
          <NumericInput unit="%" min={0} max={MAX_GST_PERCENT} step={0.01} value={shown.gstOnMdrPercent} disabled={!canConfigure}
            onChange={e => change({ gstOnMdrPercent: e.target.value })} />
        </FormField>
        <label className="flex min-h-11 items-center gap-2 text-sm text-ink-2 cursor-pointer select-none whitespace-nowrap">
          <input type="checkbox" checked={shown.enabled} disabled={!canConfigure}
            onChange={e => change({ enabled: e.target.checked })} className="rounded accent-primary w-4 h-4" />
          {t('alerts.enabled')}
        </label>
      </div>
      {canConfigure && isDirty && (
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" onClick={() => setDraft(null)} disabled={isSaving} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <button type="button" onClick={() => void save()} disabled={isSaving || mdrInvalid || gstInvalid} className="ui-btn ui-btn-primary">
            {isSaving
              ? <><Spinner /><span>{t('common:actions.saving')}</span></>
              : <><Check size={14} strokeWidth={2.5} aria-hidden="true" /><span>{t('common:actions.save')}</span></>}
          </button>
        </div>
      )}
    </div>
  )
}
