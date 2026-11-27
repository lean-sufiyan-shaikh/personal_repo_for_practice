import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { ClientVehicle, SaveVehicleInput } from '../../../api/vehicleApi'
import { FormField } from '../../../components/FormField'
import { FormGrid } from '../../../components/layout/FormGrid'
import { LoadingButton } from '../../../components/LoadingButton'
import { codeLabel } from '../../../constants/labels'
import { FUEL_LABELS } from '../../../constants/fuel'
import { normaliseRegistration } from './vehicleEntry'

interface VehicleFormProps {
  /** Null to add a vehicle. */
  vehicle: ClientVehicle | null
  isSaving: boolean
  error: string | null
  onSave: (input: SaveVehicleInput) => void
  onCancel: () => void
}

const FUEL_TYPES = Object.keys(FUEL_LABELS)

function limitOrNull(text: string): number | null {
  const value = Number(text)
  return text.trim() === '' || !Number.isFinite(value) ? null : Math.round(value * 100) / 100
}

/** Adds or edits one vehicle: registration, the fuels it may take, monthly limits and its usual driver. */
export function VehicleForm({ vehicle, isSaving, error, onSave, onCancel }: VehicleFormProps) {
  const { t } = useTranslation(['credit', 'common'])
  const [registration, setRegistration] = useState(vehicle?.registration ?? '')
  const [fuels, setFuels] = useState<string[]>(vehicle?.allowedFuelTypes ?? [])
  const [litreLimit, setLitreLimit] = useState(vehicle?.monthlyLitreLimit != null ? String(vehicle.monthlyLitreLimit) : '')
  const [amountLimit, setAmountLimit] = useState(vehicle?.monthlyAmountLimit != null ? String(vehicle.monthlyAmountLimit) : '')
  const [driverName, setDriverName] = useState(vehicle?.defaultDriverName ?? '')
  const [driverPhone, setDriverPhone] = useState(vehicle?.defaultDriverPhone ?? '')
  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  const toggleFuel = (fuel: string) =>
    setFuels((current) => (current.includes(fuel) ? current.filter((f) => f !== fuel) : [...current, fuel]))

  const submit = () => onSave({
    registration: normaliseRegistration(registration),
    allowedFuelTypes: fuels,
    monthlyLitreLimit: limitOrNull(litreLimit),
    monthlyAmountLimit: limitOrNull(amountLimit),
    defaultDriverName: driverName.trim() || null,
    defaultDriverPhone: driverPhone.trim() || null,
  })

  return (
    <form className="ui-card space-y-3 p-4" onSubmit={(e) => { e.preventDefault(); submit() }}>
      <h3 className="text-sm font-semibold text-ink">
        {vehicle ? t('vehicles.editTitle', { registration: vehicle.registration }) : t('vehicles.addTitle')}
      </h3>
      <FormGrid>
        <FormField label={t('vehicles.fields.registration')} help={t('vehicles.fields.registrationHelp')}>
          <input type="text" required maxLength={20} autoCapitalize="characters" value={registration}
            placeholder={t('vehicles.fields.registrationPlaceholder')}
            onChange={(e) => setRegistration(e.target.value)} />
        </FormField>
        <FormField label={<>{t('vehicles.fields.driverName')} {optional}</>}>
          <input type="text" maxLength={100} value={driverName} onChange={(e) => setDriverName(e.target.value)} />
        </FormField>
        <FormField label={<>{t('vehicles.fields.litreLimit')} {optional}</>}>
          <input type="number" min="0.01" step="0.01" inputMode="decimal" value={litreLimit}
            onChange={(e) => setLitreLimit(e.target.value)} />
        </FormField>
        <FormField label={<>{t('vehicles.fields.amountLimit')} {optional}</>}>
          <input type="number" min="0.01" step="0.01" inputMode="decimal" value={amountLimit}
            onChange={(e) => setAmountLimit(e.target.value)} />
        </FormField>
        <FormField label={<>{t('vehicles.fields.driverPhone')} {optional}</>}>
          <input type="tel" inputMode="numeric" maxLength={10} value={driverPhone}
            onChange={(e) => setDriverPhone(e.target.value.replace(/\D/g, ''))} />
        </FormField>
      </FormGrid>
      <fieldset>
        <legend className="ui-label">{t('vehicles.fields.allowedFuels')}</legend>
        <p className="ui-help mb-2">{t('vehicles.fields.allowedFuelsHelp')}</p>
        <div className="flex flex-wrap gap-3">
          {FUEL_TYPES.map((fuel) => (
            <label key={fuel} className="flex items-center gap-2 text-sm text-ink">
              <input type="checkbox" checked={fuels.includes(fuel)} onChange={() => toggleFuel(fuel)} />
              {codeLabel(fuel)}
            </label>
          ))}
        </div>
      </fieldset>
      {error && <p role="alert" className="ui-alert ui-alert-danger text-sm">{error}</p>}
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} disabled={isSaving} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="submit" isLoading={isSaving} loadingLabel={t('vehicles.saving')} className="ui-btn-primary">
          {t('vehicles.save')}
        </LoadingButton>
      </div>
    </form>
  )
}
