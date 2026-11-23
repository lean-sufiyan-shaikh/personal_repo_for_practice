import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, ChevronRight, Clock, Gauge, IndianRupee, Zap } from 'lucide-react'
import { pumpApi, type PumpOverviewSummary } from '../../../api/pumpApi'
import { Reveal } from '../../../components/Reveal'
import { SkeletonRows, SkeletonStat } from '../../../components/Skeleton'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { usePumpStore } from '../../../store/usePumpStore'
import { StatCard } from './StatCard'

/** Switches the Overview between the selected pump and all pumps; hidden unless the user has two or more pumps. */
export function OverviewScopeToggle({ pumpCount, selectedPumpName, showAllPumps, onShowAllPumpsChange }: {
  pumpCount: number
  selectedPumpName: string | undefined
  showAllPumps: boolean
  onShowAllPumpsChange: (showAllPumps: boolean) => void
}) {
  const { t } = useTranslation('dashboard')
  if (pumpCount < 2) return null

  return (
    <div role="group" aria-label={t('allPumps.scope')} className="ui-dashboard-pump-switcher">
      <button
        type="button"
        onClick={() => onShowAllPumpsChange(true)}
        aria-pressed={showAllPumps}
        className={`ui-dashboard-pump-chip ${showAllPumps ? 'ui-dashboard-pump-chip--active' : ''}`}
      >
        {t('allPumps.allPumps')}
      </button>
      <button
        type="button"
        onClick={() => onShowAllPumpsChange(false)}
        aria-pressed={!showAllPumps}
        className={`ui-dashboard-pump-chip ${showAllPumps ? '' : 'ui-dashboard-pump-chip--active'}`}
      >
        {selectedPumpName ?? t('allPumps.selectedPump')}
      </button>
    </div>
  )
}

function PumpRow({ pump, onSelect }: { pump: PumpOverviewSummary; onSelect: (pumpId: number) => void }) {
  const { t } = useTranslation('dashboard')
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
            <StatusBadge tone="warn" icon={Clock} label={t('allPumps.overdueShifts', { count: pump.overdueShiftCount })} />
          )}
          {pump.lowStockTankCount > 0 && (
            <StatusBadge tone="bad" icon={Gauge} label={t('allPumps.lowTanks', { count: pump.lowStockTankCount })} />
          )}
          {pump.fuelPricesStale && (
            <StatusBadge tone="bad" icon={AlertTriangle} label={t('allPumps.pricesNotUpdated')} />
          )}
        </div>
        <div className="ui-record-row__meta">
          <span>{t('allPumps.activeShifts', { count: pump.openShiftCount })}</span>
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
  const { t } = useTranslation('dashboard')
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
        {t('allPumps.loadError')}
      </div>
    )
  }

  if (isLoading || !overview) {
    return (
      <div className="space-y-5" aria-busy="true" aria-label={t('allPumps.loading')}>
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
            label={t('stats.activeShifts')}
            value={totals.openShiftCount}
            sub={totals.overdueShiftCount > 0 ? t('stats.shiftsOverdue', { count: totals.overdueShiftCount }) : t('stats.noneOverdue')}
            color="bg-ok"
            icon={Zap}
            alert={totals.overdueShiftCount > 0}
          />
          <StatCard
            label={t('stats.todaysRevenue')}
            value={totals.todaySales}
            money
            sub={t('stats.acrossPumps', { count: pumps.length })}
            color="bg-primary"
            icon={IndianRupee}
          />
          <StatCard
            label={t('stats.lowStockTanks')}
            value={totals.lowStockTankCount}
            sub={totals.lowStockTankCount === 0 ? t('stats.allTanksOk') : t('stats.needAttention')}
            color={totals.lowStockTankCount > 0 ? 'bg-bad' : 'bg-ink-3'}
            icon={Gauge}
            alert={totals.lowStockTankCount > 0}
          />
          <StatCard
            label={t('stats.staleFuelPrices')}
            value={totals.stalePricePumpCount}
            sub={totals.stalePricePumpCount === 0 ? t('stats.allPricesUpdated') : t('stats.pumpsToUpdate')}
            color={totals.stalePricePumpCount > 0 ? 'bg-bad' : 'bg-ink-3'}
            icon={AlertTriangle}
            alert={totals.stalePricePumpCount > 0}
          />
        </div>
      </Reveal>

      <Reveal delay={195}>
        <section aria-label={t('allPumps.pumps')} className="ui-card p-0 overflow-hidden">
          <div className="ui-toolbar">
            <p className="ui-toolbar-title">{t('allPumps.pumps')}</p>
            <p className="text-xs text-ink-3">{t('allPumps.selectHint')}</p>
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
