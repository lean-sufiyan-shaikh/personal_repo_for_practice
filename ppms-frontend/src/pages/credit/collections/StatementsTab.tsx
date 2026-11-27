import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { lastCompletedMonth } from '../creditDates'
import { MonthlyStatementList } from './MonthlyStatementList'

/** Credit → Statements: every client's stored statement for one month, last month first. */
export function StatementsTab({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('collections')
  const latestMonth = lastCompletedMonth().periodFrom.slice(0, 7)
  const [month, setMonth] = useState(latestMonth)
  const { data: statements = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['collections', pumpId, 'statements', month],
    queryFn: () => collectionApi.getStatementsOfMonth(pumpId, month),
    enabled: /^\d{4}-\d{2}$/.test(month),
  })

  return (
    <div className="space-y-3">
      <label className="block w-full max-w-xs">
        <span className="ui-label">{t('statements.month')}</span>
        <input type="month" value={month} max={latestMonth} onChange={(e) => setMonth(e.target.value)} />
      </label>
      {isLoading ? <SkeletonRows count={4} />
        : isError ? <LoadError onRetry={() => refetch()} message={t('statements.loadFailed')} />
        : statements.length === 0 ? <p className="ui-empty">{t('statements.empty')}</p>
        : <MonthlyStatementList pumpId={pumpId} statements={statements} showClient />}
    </div>
  )
}
