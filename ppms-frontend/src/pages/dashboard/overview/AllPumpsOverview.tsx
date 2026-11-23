import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, ChevronRight, Clock, Gauge, IndianRupee, Zap } from 'lucide-react'
import { pumpApi, type PumpOverviewSummary } from '../../../api/pumpApi'
import { Reveal } from '../../../components/Reveal'
import { SkeletonRows, SkeletonStat } from '../../../components/Skeleton'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { usePumpStore } from '../../../store/usePumpStore'
import { StatCard } from './StatCard'

function plural(count: number, word: string) {
  return `${count} ${word}${count !== 1 ? 's' : ''}`
}

/** Switches the Overview between the selected pump and all pumps; hidden unless the user has two or more pumps. */
export function OverviewScopeToggle({ pumpCount, selectedPumpName, showAllPumps, onShowAllPumpsChange }: {
  pumpCount: number
  selectedPumpName: string | undefined
  showAllPumps: boolean
  onShowAllPumpsChange: (showAllPumps: boolean) => void
}) {
  if (pumpCount < 2) return null

  return (
    <div role="group" aria-label="Overview scope" className="ui-dashboard-pump-switcher">
      <button
        type="button"
        onClick={() => onShowAllPumpsChange(true)}
        aria-pressed={showAllPumps}
        className={`ui-dashboard-pump-chip ${showAllPumps ? 'ui-dashboard-pump-chip--active' : ''}`}
      >
        All pumps
      </button>
      <button
        type="button"
        onClick={() => onShowAllPumpsChange(false)}
        aria-pressed={!showAllPumps}
        className={`ui-dashboard-pump-chip ${showAllPumps ? '' : 'ui-dashboard-pump-chip--active'}`}
      >
        {selectedPumpName ?? 'Selected pump'}
      </button>
    </div>
  )
}

function PumpRow({ pump, onSelect }: { pump: PumpOverviewSummary; onSelect: (pumpId: number) => void }) {
  return (
    <button
      type="button"
      onClick={() => onSelect(pump.pumpId)}
      className="ui-record-row w-full text-left cursor-pointer transition-colors hover:bg-bg focus-visible:bg-bg"
    >
      <div className="ui-record-row__main">
        <div className="ui-record-row__title">
          <span className="text-sm font-semibold text-ink">{pump.name}</span>
          {pump.overdueShiftCount > 0 && (
            <StatusBadge tone="warn" icon={Clock} label={plural(pump.overdueShiftCount, 'overdue shift')} />
          )}
          {pump.lowStockTankCount > 0 && (
            <StatusBadge tone="bad" icon={Gauge} label={plural(pump.lowStockTankCount, 'low tank')} />
          )}
          {pump.fuelPricesStale && (
            <StatusBadge tone="bad" icon={AlertTriangle} label="Prices not updated today" />
          )}
        </div>
        <div className="ui-record-row__meta">
          <span>{plural(pump.openShiftCount, 'active shift')}</span>
        </div>
      </div>
      <div className="ui-record-row__actions">
        <MoneyText value={pump.todaySales} className="text-base font-bold text-ink" />
        <ChevronRight size={16} className="text-ink-3" aria-hidden="true" />
      </div>
    </button>
  )
}

/** Today's totals across all of the user's pumps, with one row per pump that opens that pump's own overview. */
export function AllPumpsOverview({ onPumpSelected }: { onPumpSelected: () => void }) {
  const setSelectedPumpId = usePumpStore(state => state.setSelectedPumpId)

  const { data: overview, isLoading, isError } = useQuery({
    queryKey: ['pumpsOverview'],
    queryFn: pumpApi.getPumpsOverview,
    refetchInterval: 60_000,
  })

  const selectPump = (pumpId: number) => {
    setSelectedPumpId(pumpId)
    onPumpSelected()
  }

  if (isError) {
    return (
      <div className="ui-alert ui-alert-warning">
        Figures for your pumps could not be loaded. Refresh to try again.
      </div>
    )
  }

  if (isLoading || !overview) {
    return (
      <div className="space-y-5" aria-busy="true" aria-label="Loading all pumps">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <SkeletonStat />
          <SkeletonStat />
          <SkeletonStat />
          <SkeletonStat />
        </div>
        <div className="ui-card p-0 overflow-hidden">
          <SkeletonRows count={3} />
        </div>
      </div>
    )
  }

  const { totals, pumps } = overview

  return (
    <div className="space-y-5">
      <Reveal delay={150}>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <StatCard
            label="Active Shifts"
            value={totals.openShiftCount}
            sub={totals.overdueShiftCount > 0 ? `${plural(totals.overdueShiftCount, 'shift')} overdue` : 'None overdue'}
            color="bg-ok"
            icon={Zap}
            alert={totals.overdueShiftCount > 0}
          />
          <StatCard
            label="Today's Revenue"
            value={totals.todaySales}
            money
            sub={`Across ${plural(pumps.length, 'pump')}`}
            color="bg-primary"
            icon={IndianRupee}
          />
          <StatCard
            label="Low Stock Tanks"
            value={totals.lowStockTankCount}
            sub={totals.lowStockTankCount === 0 ? 'All tanks OK' : 'Need attention'}
            color={totals.lowStockTankCount > 0 ? 'bg-bad' : 'bg-ink-3'}
            icon={Gauge}
            alert={totals.lowStockTankCount > 0}
          />
          <StatCard
            label="Stale Fuel Prices"
            value={totals.stalePricePumpCount}
            sub={totals.stalePricePumpCount === 0 ? 'All prices updated today' : 'Pumps to update in Setup'}
            color={totals.stalePricePumpCount > 0 ? 'bg-bad' : 'bg-ink-3'}
            icon={AlertTriangle}
            alert={totals.stalePricePumpCount > 0}
          />
        </div>
      </Reveal>

      <Reveal delay={195}>
        <section aria-label="Pumps" className="ui-card p-0 overflow-hidden">
          <div className="ui-toolbar">
            <p className="ui-toolbar-title">Pumps</p>
            <p className="text-xs text-ink-3">Select a pump to open its overview</p>
          </div>
          <div className="ui-record-list">
            {pumps.map(pump => (
              <PumpRow key={pump.pumpId} pump={pump} onSelect={selectPump} />
            ))}
          </div>
        </section>
      </Reveal>
    </div>
  )
}
