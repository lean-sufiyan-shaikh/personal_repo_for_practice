import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { ArrowDownToLine, Calculator, Settings2 } from 'lucide-react'
import { pettyCashApi } from '../../../api/pettyCashApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { useUrlParam } from '../../../components/layout/useUrlParam'
import { PettyCashSummaryCard } from './PettyCashSummaryCard'
import { PettyCashLedger } from './PettyCashLedger'
import { TopUpPettyCashDialog } from './TopUpPettyCashDialog'
import { CountPettyCashDialog } from './CountPettyCashDialog'
import { PettyCashSettingsDialog } from './PettyCashSettingsDialog'

const TOP_UP_ACTION = 'top-up-petty'
const COUNT_ACTION = 'count-petty'
const SETTINGS_ACTION = 'petty-settings'
const COUNT_FOR_PARAM = 'countFor'

interface Props {
  pumpId: number
  canManage: boolean
  canSetFloat: boolean
}

/**
 * The petty cash box: its float, balance and last count, and its ledger with the balance after each movement.
 * ?action=count-petty&countFor=YYYY-MM-DD opens the count for that business day, as the Day Close checklist links it.
 */
export function PettyCashTab({ pumpId, canManage, canSetFloat }: Props) {
  const { t } = useTranslation('cash')
  const action = useUrlParam('action')
  const [searchParams, setSearchParams] = useSearchParams()
  const closeAction = () => setSearchParams((params) => {
    const updated = new URLSearchParams(params)
    updated.delete('action')
    updated.delete(COUNT_FOR_PARAM)
    return updated
  }, { replace: true })
  const { data: summary, isLoading, isError, refetch } = useQuery({
    queryKey: ['pettyCash', pumpId],
    queryFn: () => pettyCashApi.getSummary(pumpId),
  })

  return (
    <div className="space-y-(--density-gap)">
      {isLoading ? (
        <div className="ui-card"><SkeletonRows count={2} /></div>
      ) : isError || !summary ? (
        <LoadError message={t('petty.loadFailed')} onRetry={() => void refetch()} />
      ) : (
        <PettyCashSummaryCard summary={summary} />
      )}

      {canManage && (
        <div className="grid grid-cols-1 gap-2 sm:flex">
          <button type="button" onClick={() => action.set(TOP_UP_ACTION)} disabled={!summary}
            className="ui-btn ui-btn-primary min-h-11 inline-flex items-center justify-center gap-1.5">
            <ArrowDownToLine size={16} aria-hidden="true" />{t('petty.topUp')}
          </button>
          <button type="button" onClick={() => action.set(COUNT_ACTION)} disabled={!summary}
            className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Calculator size={16} aria-hidden="true" />{t('petty.count')}
          </button>
          {canSetFloat && (
            <button type="button" onClick={() => action.set(SETTINGS_ACTION)} disabled={!summary}
              className="ui-btn ui-btn-ghost min-h-11 inline-flex items-center justify-center gap-1.5">
              <Settings2 size={16} aria-hidden="true" />{t('petty.settings')}
            </button>
          )}
        </div>
      )}

      <PettyCashLedger pumpId={pumpId} />

      {summary && canManage && action.value === TOP_UP_ACTION && (
        <TopUpPettyCashDialog pumpId={pumpId} summary={summary} onClose={closeAction} />
      )}
      {summary && canManage && action.value === COUNT_ACTION && (
        <CountPettyCashDialog pumpId={pumpId} summary={summary} initialDate={searchParams.get(COUNT_FOR_PARAM)}
          onClose={closeAction} />
      )}
      {summary && canSetFloat && action.value === SETTINGS_ACTION && (
        <PettyCashSettingsDialog pumpId={pumpId} summary={summary} onClose={closeAction} />
      )}
    </div>
  )
}
