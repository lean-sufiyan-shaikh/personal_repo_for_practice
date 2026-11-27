import { useId } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { vehicleApi } from '../../../api/vehicleApi'
import type { ClientVehicle } from '../../../api/vehicleApi'
import { FormField } from '../../../components/FormField'
import { FormGrid } from '../../../components/layout/FormGrid'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency, formatLitres } from '../../../utils/format'
import { matchingVehicle, vehicleNotice } from './vehicleEntry'
import type { VehicleEntryValue } from './vehicleEntry'

interface VehicleDriverFieldsProps {
  pumpId: number
  /** The client the entry is billed to (a sub-account when one is picked); null until a client is chosen. */
  clientId: number | null
  value: VehicleEntryValue
  onChange: (value: VehicleEntryValue) => void
  /** Null for a counter sale, which no fuel rule applies to. */
  fuelType: string | null
  amount: number
  litres: number | null
}

/**
 * The vehicle and driver of a credit entry. A BLOCK account picks from its listed vehicles; otherwise the registration
 * may also be typed in. Says before saving whether the vehicle check will refuse or flag the entry.
 */
export function VehicleDriverFields({ pumpId, clientId, value, onChange, fuelType, amount, litres }: VehicleDriverFieldsProps) {
  const { t } = useTranslation(['credit', 'common'])
  const listId = useId()
  const labelId = useId()
  const { data: picker } = useQuery({
    queryKey: ['vehiclePicker', pumpId, clientId],
    queryFn: () => vehicleApi.getVehiclePicker(pumpId, clientId!),
    enabled: clientId != null,
  })
  if (clientId == null) return null

  const vehicles = picker?.vehicles ?? []
  const isBlock = picker?.vehicleCheck === 'BLOCK'
  const selected = vehicles.find((vehicle) => vehicle.id === value.vehicleId)
  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  const pick = (vehicle: ClientVehicle | undefined, registration: string) => onChange({
    ...value,
    vehicleId: vehicle?.id ?? null,
    registration: vehicle?.registration ?? registration,
    driverName: vehicle && !value.driverName ? vehicle.defaultDriverName ?? '' : value.driverName,
    driverPhone: vehicle && !value.driverPhone ? vehicle.defaultDriverPhone ?? '' : value.driverPhone,
  })

  const notice = vehicleNotice(picker, value, {
    fuelType, amount, litres, formatLitres, formatAmount: formatCurrency, fuelLabel: codeLabel,
  })

  return (
    <div className="space-y-3">
      <div>
        <span id={labelId} className="ui-label">{t('vehicleEntry.vehicle')} {!isBlock && optional}</span>
        {isBlock ? (
          <SearchableSelect aria-labelledby={labelId}
            value={value.vehicleId != null ? String(value.vehicleId) : ''}
            onChange={(id) => pick(vehicles.find((vehicle) => String(vehicle.id) === id), '')}
            placeholder={t('vehicleEntry.pickPlaceholder')}
            options={vehicles.map((vehicle) => ({ value: String(vehicle.id), label: vehicle.registration }))}
          />
        ) : (
          <>
            <input type="text" aria-labelledby={labelId} list={listId} autoCapitalize="characters"
              value={value.registration}
              placeholder={t('vehicleEntry.typePlaceholder')}
              onChange={(e) => pick(matchingVehicle(vehicles, e.target.value), e.target.value)} />
            <datalist id={listId}>
              {vehicles.map((vehicle) => <option key={vehicle.id} value={vehicle.registration} />)}
            </datalist>
          </>
        )}
        {selected && <p className="ui-help">{vehicleSummary(selected, t)}</p>}
      </div>

      {notice && (
        <p role="alert" className={`ui-alert text-sm ${notice.tone === 'danger' ? 'ui-alert-danger' : 'ui-alert-warning'}`}>
          {t(notice.key, notice.params)}
        </p>
      )}

      <FormGrid>
        <FormField label={<>{t('vehicleEntry.driverName')} {optional}</>}>
          <input type="text" value={value.driverName} maxLength={100}
            onChange={(e) => onChange({ ...value, driverName: e.target.value })} />
        </FormField>
        <FormField label={<>{t('vehicleEntry.driverPhone')} {optional}</>}>
          <input type="tel" inputMode="numeric" value={value.driverPhone} maxLength={10}
            onChange={(e) => onChange({ ...value, driverPhone: e.target.value.replace(/\D/g, '') })} />
        </FormField>
      </FormGrid>
    </div>
  )
}

function vehicleSummary(vehicle: ClientVehicle, t: TFunction<['credit', 'common']>): string {
  const fuels = vehicle.allowedFuelTypes.length === 0
    ? t('vehicles.anyFuel')
    : vehicle.allowedFuelTypes.map((fuel) => codeLabel(fuel)).join(', ')
  const left = [
    vehicle.litresLeft != null ? formatLitres(vehicle.litresLeft) : null,
    vehicle.amountLeft != null ? formatCurrency(vehicle.amountLeft) : null,
  ].filter((part): part is string => part !== null)
  return left.length > 0
    ? t('vehicleEntry.summaryWithLimit', { fuels, left: left.join(' · ') })
    : t('vehicleEntry.summary', { fuels })
}
