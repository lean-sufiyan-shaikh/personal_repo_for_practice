import { useTranslation } from 'react-i18next'
import type { ClientVehicle } from '../../../api/vehicleApi'
import { StatusBadge } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency, formatLitres } from '../../../utils/format'

interface VehicleCardProps {
  vehicle: ClientVehicle
  /** Shown when the list covers sub-accounts, whose vehicles belong to someone else than the client opened. */
  showOwner: boolean
  isBusy: boolean
  onEdit: () => void
  onToggleActive: () => void
}

/** One vehicle: its fuels, this month's use against its limits, its usual driver, and its actions. */
export function VehicleCard({ vehicle, showOwner, isBusy, onEdit, onToggleActive }: VehicleCardProps) {
  const { t } = useTranslation('credit')
  const fuels = vehicle.allowedFuelTypes.length === 0
    ? t('vehicles.anyFuel')
    : vehicle.allowedFuelTypes.map((fuel) => codeLabel(fuel)).join(', ')

  return (
    <li className="flex flex-wrap items-start justify-between gap-3 px-4 py-3">
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-sm font-semibold text-ink">{vehicle.registration}</span>
          {!vehicle.active && <StatusBadge tone="neutral" label={t('vehicles.inactive')} />}
          {showOwner && <span className="text-xs text-ink-3">{t('vehicles.owner', { name: vehicle.clientName })}</span>}
        </div>
        <p className="text-xs text-ink-3">{fuels}</p>
        <p className="text-xs text-ink-2">
          {t('vehicles.usedThisMonth', {
            litres: formatLitres(vehicle.litresThisMonth), amount: formatCurrency(vehicle.amountThisMonth),
          })}
        </p>
        <p className="text-xs text-ink-3">
          {vehicle.monthlyLitreLimit == null && vehicle.monthlyAmountLimit == null
            ? t('vehicles.noLimit')
            : [
                vehicle.monthlyLitreLimit != null && t('vehicles.litreLimit', {
                  limit: formatLitres(vehicle.monthlyLitreLimit), left: formatLitres(vehicle.litresLeft ?? 0),
                }),
                vehicle.monthlyAmountLimit != null && t('vehicles.amountLimit', {
                  limit: formatCurrency(vehicle.monthlyAmountLimit), left: formatCurrency(vehicle.amountLeft ?? 0),
                }),
              ].filter(Boolean).join(' · ')}
        </p>
        {vehicle.defaultDriverName && (
          <p className="text-xs text-ink-3">
            {t('vehicles.driver', { name: vehicle.defaultDriverName })}
            {vehicle.defaultDriverPhone && ` · ${vehicle.defaultDriverPhone}`}
          </p>
        )}
      </div>
      <div className="flex shrink-0 gap-2">
        <button type="button" onClick={onEdit} disabled={isBusy} className="ui-btn ui-btn-secondary ui-btn-compact"
          aria-label={t('vehicles.editAria', { registration: vehicle.registration })}>
          {t('vehicles.edit')}
        </button>
        <button type="button" onClick={onToggleActive} disabled={isBusy} className="ui-btn ui-btn-ghost ui-btn-compact"
          aria-label={t(vehicle.active ? 'vehicles.deactivateAria' : 'vehicles.reactivateAria', { registration: vehicle.registration })}>
          {vehicle.active ? t('vehicles.deactivate') : t('vehicles.reactivate')}
        </button>
      </div>
    </li>
  )
}
