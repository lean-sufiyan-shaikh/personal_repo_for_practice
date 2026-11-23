import { Link } from 'react-router-dom'
import type { TankStock } from '../../../api/inventoryApi'
import { Reveal } from '../../../components/Reveal'
import { StatusBadge } from '../../../components/StatusBadge'
import { FUEL_STOCK_COLORS } from '../../../constants/fuel'
import { codeLabel } from '../../../constants/labels'

export function TankStockOverview({ tanks: allTanks }: { tanks: TankStock[] }) {
  return (
    <>
    {allTanks.length > 0 && (
      <Reveal delay={295}>
      <div className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <p className="ui-toolbar-title">Tank Stock Overview</p>
          <Link to="/dashboard/inventory"
            className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong">
            Full inventory →
          </Link>
        </div>
        <div className="px-5 py-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
          {allTanks.map(tank => {
            const pct = Math.min(100, Math.max(0, tank.stockPercentage))
            const barColor = tank.lowStock ? 'bg-bad' : FUEL_STOCK_COLORS[tank.fuelType]?.bar ?? 'bg-control'

            return (
            <div key={tank.tankId} className="ui-card-plain ui-card-muted px-3 py-2.5">
                <div className="flex items-center justify-between mb-1.5">
                  <div className="flex items-center gap-1.5">
                    <span className="text-xs font-semibold text-ink">{tank.tankIdentifier}</span>
                    <span className="text-xs text-ink-3">{codeLabel(tank.fuelType)}</span>
                    {tank.lowStock && <StatusBadge tone="bad" label="Low" />}
                  </div>
                  <span className={`text-xs font-medium ${tank.lowStock ? 'text-bad' : 'text-ink-3'}`}>
                    {pct.toFixed(0)}%
                  </span>
                </div>
                <div
                  className="w-full h-2 bg-surface-sunken rounded-full overflow-hidden"
                  role="progressbar"
                  aria-valuenow={pct}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-label={`${tank.tankIdentifier} stock: ${pct.toFixed(0)}%${tank.lowStock ? ' – low stock' : ''}`}
                >
                  <div
                    className={`h-full rounded-full transition-all duration-700 ${barColor} ${tank.lowStock ? 'animate-pulse' : ''}`}
                    style={{ width: `${pct}%` }}
                  />
                </div>
                <p className="text-xs text-ink-3 mt-1">
                  {tank.currentStock.toLocaleString('en-IN', { minimumFractionDigits: 0 })} L remaining
                </p>
              </div>
            )
          })}
        </div>
      </div>
      </Reveal>
    )}
    </>
  )
}
