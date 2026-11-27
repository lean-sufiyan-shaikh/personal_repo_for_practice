import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { MonthlyStatementList } from './MonthlyStatementList'

/** One client's stored monthly statements, newest first. */
export function ClientStatementsTab({ pumpId, clientId }: { pumpId: number; clientId: number }) {
  const { t } = useTranslation('collections')
  const { data: statements = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['collections', pumpId, 'statements', 'client', clientId],
    queryFn: () => collectionApi.getStatementsOfClient(pumpId, clientId),
  })

  if (isLoading) return <SkeletonRows count={3} />
  if (isError) return <LoadError onRetry={() => refetch()} message={t('statements.loadFailed')} />
  if (statements.length === 0) return <p className="ui-empty">{t('statements.clientEmpty')}</p>
  return <MonthlyStatementList pumpId={pumpId} statements={statements} showClient={false} />
}
