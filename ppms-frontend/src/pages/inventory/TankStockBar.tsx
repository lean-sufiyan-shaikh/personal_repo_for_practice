import { AlertTriangle, Clock } from 'lucide-react'
import type { TankStock } from '../../api/inventoryApi'
import { FUEL_COLORS } from './inventoryFormat'
import { codeLabel } from '../../constants/labels'
import { StatusBadge } from '../../components/StatusBadge'
import { LitresText } from '../../components/AmountText'

// ── Tank stock bar ─────────────────────────────────────────────────────────────

export function TankStockBar({
  tank,
  dipOverdue,
  lastDipAt,
  now,
  onDipCheck,
  onViewLots,
}: {
  tank: TankStock
  dipOverdue: boolean
  lastDipAt: Date | undefined
  now: number
  onDipCheck?: () => void
  onViewLots: () => void
}) {
  const pct  = Math.min(100, Math.max(0, tank.stockPercentage))
  const cols = FUEL_COLORS[tank.fuelType] ?? FUEL_COLORS.PETROL
  const isLow = tank.lowStock

  // Human-readable time since last DIP (e.g. "8 h ago", "3 d ago")
  function timeSinceDip(d: Date) {
    const diffMs = now - d.getTime()
    const h = Math.floor(diffMs / (1000 * 60 * 60))
    if (h < 24) return `${h} h ago`
    return `${Math.floor(h / 24)} d ago`
  }

  return (
    <div className={`px-5 py-4 ${dipOverdue ? 'bg-bad-bg' : ''}`}>
      <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
        <div className="flex items-center gap-2 flex-wrap min-w-0">
          <span className={`text-xs font-bold px-2 py-0.5 rounded-badge ${cols.badge}`}>
            {codeLabel(tank.fuelType)}
          </span>
          <span className="text-sm font-semibold text-ink">{tank.tankIdentifier}</span>
          {isLow && (
            <StatusBadge tone="bad" icon={AlertTriangle} label="LOW STOCK" className="animate-pulse" />
          )}
          {dipOverdue && (
            <StatusBadge
              tone="bad"
              icon={Clock}
              label={`DIP OVERDUE ${lastDipAt ? `· last ${timeSinceDip(lastDipAt)}` : '· never checked'}`}
            />
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className={`text-xs font-medium ${isLow ? 'text-bad' : 'text-ink-3'}`}>
            {pct.toFixed(1)}%
          </span>
          <button
            onClick={onViewLots}
            className="ui-btn ui-btn-secondary ui-btn-compact"
          >
            View Lots
          </button>
          {onDipCheck && (
            <button
              onClick={onDipCheck}
              className="ui-btn ui-btn-secondary ui-btn-compact"
            >
              DIP Check
            </button>
          )}
        </div>
      </div>

      {/* Animated stock bar */}
      <div className="w-full h-4 bg-surface-sunken rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full transition-all duration-700 ease-out ${
            isLow ? 'bg-bad animate-pulse' : cols.bar
          }`}
          style={{ width: `${pct}%` }}
        />
      </div>

      <div className="flex items-center justify-between mt-1.5 text-xs text-ink-3">
        <span>
          <LitresText value={tank.currentStock} className={`font-semibold ${isLow ? 'text-bad' : 'text-ink'}`} />
          {' '}remaining
        </span>
        <span>Capacity: {tank.capacity.toLocaleString('en-IN', { minimumFractionDigits: 0 })} L</span>
      </div>
    </div>
  )
}

// ── Fuel Lots Dialog ──────────────────────────────────────────────────────────
/**
 * Read-only dialog showing ACTIVE FIFO inventory lots for a specific tank.
 * Columns: In Date, Invoice (Bill No), Cost Price/L, Remaining (L), Original (L).
 * DIP-adjustment lots are labelled "DIP Adj." in the Invoice column and show 0 cost.
 * No edit — view only.
 */
