import { useTranslation } from 'react-i18next'
import type { VehicleCheckMode } from '../../../api/vehicleApi'

const MODES: VehicleCheckMode[] = ['OFF', 'WARN', 'BLOCK']

interface VehicleCheckSettingProps {
  mode: VehicleCheckMode
  /** Null when the check is the client's own; the parent's name for a sub-account, whose check is set there. */
  setOnParentName: string | null
  isSaving: boolean
  onChange: (mode: VehicleCheckMode) => void
}

/** What a credit entry for a vehicle outside the account's rules does: nothing, a flag, or a refusal. */
export function VehicleCheckSetting({ mode, setOnParentName, isSaving, onChange }: VehicleCheckSettingProps) {
  const { t } = useTranslation('credit')

  if (setOnParentName != null) {
    return (
      <p className="ui-card-plain px-4 py-2.5 text-sm text-ink-3">
        {t('vehicles.check.onParent', { name: setOnParentName, mode: t(`vehicles.check.modes.${mode}`) })}
      </p>
    )
  }

  return (
    <fieldset className="ui-card space-y-2 p-4" disabled={isSaving}>
      <legend className="text-sm font-semibold text-ink">{t('vehicles.check.label')}</legend>
      <p className="ui-help">{t('vehicles.check.hint')}</p>
      <div className="grid gap-2 sm:grid-cols-3">
        {MODES.map((option) => (
          <label key={option}
            className={`flex cursor-pointer items-start gap-2 rounded-control border p-3 text-sm ${
              option === mode ? 'border-primary bg-info-bg' : 'border-border'}`}>
            <input type="radio" name="vehicle-check" value={option} checked={option === mode}
              onChange={() => onChange(option)} className="mt-0.5" />
            <span>
              <span className="block font-medium text-ink">{t(`vehicles.check.modes.${option}`)}</span>
              <span className="block text-xs text-ink-3">{t(`vehicles.check.descriptions.${option}`)}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
