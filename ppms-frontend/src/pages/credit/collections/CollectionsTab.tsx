import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import { DashboardColumns } from '../../../components/layout/DashboardGrid'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { CollectionFigures } from './CollectionFigures'
import { AccountFigureList, PromiseLists } from './CollectionLists'
import { ToRemindList } from './ToRemindList'

interface CollectionsTabProps {
  pumpId: number
  /** Managers and above remind clients; accountants only read. */
  canRemind: boolean
}

/** Credit → Collections: what is overdue and how old, this month's collections, promises, holds and today's reminders. */
export function CollectionsTab({ pumpId, canRemind }: CollectionsTabProps) {
  const { t } = useTranslation('collections')
  const { data: dashboard, isLoading, isError, refetch } = useQuery({
    queryKey: ['collections', pumpId, 'dashboard'],
    queryFn: () => collectionApi.getDashboard(pumpId),
  })

  if (isLoading) return <SkeletonRows count={6} />
  if (isError || !dashboard) return <LoadError onRetry={() => refetch()} message={t('dashboard.loadFailed')} />

  return (
    <div className="space-y-4">
      <CollectionFigures dashboard={dashboard} />
      <ToRemindList pumpId={pumpId} reminders={dashboard.toRemind} canRemind={canRemind} />
      <DashboardColumns>
        <PromiseLists dueToday={dashboard.promisesDueToday} missed={dashboard.missedPromises} />
        <AccountFigureList title={t('dashboard.onHoldTitle')} accounts={dashboard.clientsOnHold} emptyText={t('dashboard.noneOnHold')} />
      </DashboardColumns>
      <AccountFigureList title={t('dashboard.topOverdueTitle')} accounts={dashboard.topOverdue} emptyText={t('dashboard.noneOverdue')} />
    </div>
  )
}
