import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Landmark, Vault } from 'lucide-react'
import { cashApi } from '../../../api/cashApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { useUrlParam } from '../../../components/layout/useUrlParam'
import { SafeSummaryCard } from './SafeSummaryCard'
import { PendingDropsSection } from './PendingDropsSection'
import { SafeLedger } from './SafeLedger'
import { DepositList } from './DepositList'
import { CountSafeDialog } from './CountSafeDialog'
import { BankDepositDialog } from './BankDepositDialog'

const COUNT_ACTION = 'count-safe'
const DEPOSIT_ACTION = 'bank-deposit'

/** The safe: its balance and last count, drops to confirm, the ledger and bank deposits. */
export function SafeTab({ pumpId, canManage }: { pumpId: number; canManage: boolean }) {
  const { t } = useTranslation('cash')
  const action = useUrlParam('action')
  const { data: summary, isLoading, isError, refetch } = useQuery({
    queryKey: ['safe', pumpId],
    queryFn: () => cashApi.getSafe(pumpId),
  })

  return (
    <div className="space-y-(--density-gap)">
      {isLoading ? (
        <div className="ui-card"><SkeletonRows count={2} /></div>
      ) : isError || !summary ? (
        <LoadError message={t('safe.loadFailed')} onRetry={() => void refetch()} />
      ) : (
        <SafeSummaryCard summary={summary} />
      )}

      {canManage && (
        <div className="grid grid-cols-1 gap-2 sm:flex">
          <button type="button" onClick={() => action.set(COUNT_ACTION)} disabled={!summary}
            className="ui-btn ui-btn-primary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Vault size={16} aria-hidden="true" />{t('safe.countSafe')}
          </button>
          <button type="button" onClick={() => action.set(DEPOSIT_ACTION)} disabled={!summary}
            className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Landmark size={16} aria-hidden="true" />{t('safe.recordDeposit')}
          </button>
        </div>
      )}

      {canManage && <PendingDropsSection pumpId={pumpId} />}
      <SafeLedger pumpId={pumpId} />
      <DepositList pumpId={pumpId} canAttach={canManage} />

      {summary && canManage && action.value === COUNT_ACTION && (
        <CountSafeDialog pumpId={pumpId} summary={summary} onClose={action.remove} />
      )}
      {summary && canManage && action.value === DEPOSIT_ACTION && (
        <BankDepositDialog pumpId={pumpId} balance={summary.balance} onClose={action.remove} />
      )}
    </div>
  )
}
