import { useTranslation } from 'react-i18next'
import type { TankStock } from '../../api/inventoryApi'
import { LoadError } from '../../components/LoadError'
import { SkeletonRows } from '../../components/Skeleton'
import { PageSection } from '../../components/layout/PageSection'
import { TankStockBar } from './TankStockBar'

interface CurrentStockSectionProps {
  tanks: TankStock[]
  isLoading: boolean
  loadFailed: boolean
  onRetry: () => void
  pumpName: string | undefined
  isDipOverdue: (tank: TankStock) => boolean
  lastDipByTankId: Map<number, Date>
  now: number
  onDipCheck: ((tankId: number) => void) | undefined
  onViewLots: (tank: TankStock) => void
}

export function CurrentStockSection({
  tanks, isLoading, loadFailed, onRetry, pumpName, isDipOverdue, lastDipByTankId, now, onDipCheck, onViewLots,
}: CurrentStockSectionProps) {
  const { t } = useTranslation('inventory')
  return (
    <PageSection
      title={t('page.currentStock')}
      actions={pumpName && <span className="text-xs text-ink-3">{pumpName}</span>}
    >
      {isLoading ? (
        <SkeletonRows count={3} />
      ) : loadFailed ? (
        <LoadError onRetry={onRetry} />
      ) : tanks.length === 0 ? (
        <p className="ui-empty">{t('page.noTanks')}</p>
      ) : (
        <ul className="grid grid-cols-1 gap-(--density-gap) sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {tanks.map(tank => (
            <li key={tank.tankId}>
              <TankStockBar
                tank={tank}
                dipOverdue={isDipOverdue(tank)}
                lastDipAt={lastDipByTankId.get(tank.tankId)}
                now={now}
                onDipCheck={onDipCheck ? () => onDipCheck(tank.tankId) : undefined}
                onViewLots={() => onViewLots(tank)}
              />
            </li>
          ))}
        </ul>
      )}
    </PageSection>
  )
}
