import { useTranslation } from 'react-i18next'
import { formatDateTimeIn } from '../../i18n/format'
import { formatCurrency, formatLitres } from '../../utils/format'

export interface PortalVehicleTotal {
  /** Null for credit given to unknown or unrecorded vehicles. */
  registration: string | null
  litres: number
  amount: number
  entryCount: number
}

export interface PortalVehicleMonth {
  /** yyyy-MM */
  month: string
  vehicles: PortalVehicleTotal[]
}

function monthLabel(month: string): string {
  const [year, monthNumber] = month.split('-').map(Number)
  return formatDateTimeIn(new Date(Date.UTC(year, monthNumber - 1, 15)), { month: 'long', year: 'numeric', timeZone: 'UTC' })
}

/** The fuel each vehicle took on credit this month and the month before. */
export function PortalVehicleUsage({ months }: { months: PortalVehicleMonth[] }) {
  const { t } = useTranslation('portal')
  return (
    <section aria-labelledby="portal-vehicle-usage" className="mt-4 border-t border-border pt-3">
      <h2 id="portal-vehicle-usage" className="text-sm font-semibold text-ink">{t('usage.title')}</h2>
      {months.map((month) => (
        <div key={month.month} className="mt-3">
          <h3 className="text-xs font-semibold text-ink-2">{monthLabel(month.month)}</h3>
          {month.vehicles.length === 0 ? (
            <p className="text-xs text-ink-3">{t('usage.none')}</p>
          ) : (
            <ul className="mt-1 divide-y divide-border">
              {month.vehicles.map((vehicle) => (
                <li key={vehicle.registration ?? 'unassigned'} className="flex items-baseline justify-between gap-3 py-1.5">
                  <span className="min-w-0 truncate text-sm text-ink">
                    {vehicle.registration ?? t('usage.unassigned')}
                  </span>
                  <span className="shrink-0 text-right text-xs text-ink-2">
                    {vehicle.litres > 0 && `${formatLitres(vehicle.litres)} · `}{formatCurrency(vehicle.amount)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      ))}
    </section>
  )
}
