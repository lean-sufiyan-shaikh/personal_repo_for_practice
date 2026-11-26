import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../../api/inventoryApi'
import type { TankStock } from '../../../api/inventoryApi'
import { LoadError } from '../../../components/LoadError'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { SkeletonRows } from '../../../components/Skeleton'
import { FilterBar } from '../../../components/layout/FilterBar'
import { PageSection } from '../../../components/layout/PageSection'
import { codeLabel } from '../../../constants/labels'
import { localDateInputValue } from '../../../utils/date'
import { daysInRange } from '../../../utils/datePresets'
import { ReconciliationSummaryCard } from './ReconciliationSummaryCard'
import { ReconciliationTable } from './ReconciliationTable'
import { MAX_RANGE_DAYS } from './reconciliationFormat'

const DEFAULT_DAYS = 7

/** Expected against dipped closing stock per tank and day, with running totals, for the chosen dates and tank. */
export function ReconciliationTab({ pumpId, tanks }: { pumpId: number; tanks: TankStock[] }) {
  const { t } = useTranslation(['inventory', 'common'])
  const today = localDateInputValue()
  const [from, setFrom] = useState(localDateInputValue(1 - DEFAULT_DAYS))
  const [to, setTo] = useState(today)
  const [tankFilter, setTankFilter] = useState('')

  const rangeError = !from || !to ? null
    : from > to ? t('reconciliation.rangeStartAfterEnd')
    : to > today ? t('reconciliation.rangeInFuture')
    : daysInRange({ from, to }) > MAX_RANGE_DAYS ? t('reconciliation.rangeTooLong', { days: MAX_RANGE_DAYS })
    : null
  const canLoad = Boolean(from && to) && rangeError === null
  const tankId = tankFilter ? Number(tankFilter) : undefined

  const { data, isLoading, isLoadingError, isPlaceholderData, refetch } = useQuery({
    queryKey: ['wetStockReconciliation', pumpId, from, to, tankId],
    queryFn: () => inventoryApi.getReconciliation(pumpId, from, to, tankId),
    enabled: canLoad,
    placeholderData: keepPreviousData,
  })
  const reconciledTanks = data?.tanks ?? []

  return (
    <div className="space-y-(--density-gap)">
      <FilterBar label={t('reconciliation.filterTitle')}>
        <div className="flex flex-wrap items-center gap-2">
          <input aria-label={t('common:fields.fromDate')} type="date" value={from} max={today}
            onChange={e => setFrom(e.target.value)} className="min-w-[8.5rem] text-xs text-ink" />
          <span className="text-xs text-ink-3">{t('reconciliation.to')}</span>
          <input aria-label={t('common:fields.toDate')} type="date" value={to} max={today}
            onChange={e => setTo(e.target.value)} className="min-w-[8.5rem] text-xs text-ink" />
        </div>
        <div className="w-44">
          <SearchableSelect label={t('reconciliation.filterByTank')}
            value={tankFilter}
            onChange={setTankFilter}
            options={[
              { value: '', label: t('filters.allTanks') },
              ...tanks.map(tank => ({ value: String(tank.tankId), label: tank.tankIdentifier })),
            ]}
            placeholder={t('filters.byTank')}
          />
        </div>
      </FilterBar>

      <p className="text-xs text-ink-3">{t('reconciliation.ambientNote')}</p>

      {rangeError ? (
        <p role="alert" className="ui-alert ui-alert-warning text-sm">{rangeError}</p>
      ) : isLoading ? (
        <SkeletonRows count={3} />
      ) : isLoadingError ? (
        <LoadError onRetry={() => void refetch()} />
      ) : reconciledTanks.length === 0 ? (
        <p className="ui-empty">{t('reconciliation.noTanks')}</p>
      ) : (
        <div aria-busy={isPlaceholderData} className={`space-y-(--density-gap) transition-opacity ${isPlaceholderData ? 'opacity-60' : ''}`}>
          <ul className="grid grid-cols-1 gap-(--density-gap) sm:grid-cols-2 xl:grid-cols-3">
            {reconciledTanks.map(tank => (
              <li key={tank.tankId}><ReconciliationSummaryCard tank={tank} /></li>
            ))}
          </ul>
          {reconciledTanks.map(tank => (
            <PageSection key={tank.tankId} title={t('tank.cardLabel', { tank: tank.tankIdentifier, fuel: codeLabel(tank.fuelType) })} flush>
              <ReconciliationTable tank={tank} />
            </PageSection>
          ))}
        </div>
      )}
    </div>
  )
}
