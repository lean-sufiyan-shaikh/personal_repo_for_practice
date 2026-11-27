import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { StatementImportSummary, StatementLineStatus } from '../../api/bankReconciliationApi'
import { LoadError } from '../../components/LoadError'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { SkeletonRows } from '../../components/Skeleton'
import { FilterBar } from '../../components/layout/FilterBar'
import { PageSection } from '../../components/layout/PageSection'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { LINE_STATUSES, lineAmount, lineStatusLabel } from './reconciliationFormat'
import { MatchLinePicker } from './MatchLinePicker'
import { StatementLineViews } from './StatementLineViews'
import { useStatementLineActions } from './useStatementLineActions'

type StatusFilter = StatementLineStatus | 'ALL'

interface StatementLinesSectionProps {
  pumpId: number
  statement: StatementImportSummary
  isAutoMatching: boolean
  onAutoMatch: () => void
}

/** The lines of one imported statement, filtered by status, each with the actions its status allows. */
export function StatementLinesSection({ pumpId, statement, isAutoMatching, onAutoMatch }: StatementLinesSectionProps) {
  const { t } = useTranslation('reconciliation')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const actions = useStatementLineActions(pumpId, statement.id)

  const { data: lines, isLoading, isError, refetch } = useQuery({
    queryKey: ['reconciliation-lines', pumpId, statement.id],
    queryFn: () => bankReconciliationApi.getLines(pumpId, statement.id),
  })

  const shown = (lines ?? []).filter((line) => statusFilter === 'ALL' || line.matchStatus === statusFilter)
  const countOf = (status: StatusFilter) => status === 'ALL'
    ? lines?.length ?? 0
    : (lines ?? []).filter((line) => line.matchStatus === status).length

  return (
    <PageSection
      title={t('lines.title')}
      actions={(
        <button type="button" onClick={onAutoMatch} disabled={isAutoMatching} className="ui-btn ui-btn-secondary">
          {isAutoMatching ? t('lines.autoMatching') : t('lines.autoMatch')}
        </button>
      )}
      flush
    >
      <FilterBar label={t('lines.filtersLabel')} summary={lines && t('lines.shownCount', { count: shown.length })}>
        <div className="ui-chip-row" role="group" aria-label={t('lines.status')}>
          {(['ALL', ...LINE_STATUSES] as StatusFilter[]).map((status) => (
            <button key={status} type="button" className="ui-chip" aria-pressed={statusFilter === status}
              onClick={() => setStatusFilter(status)}>
              {status === 'ALL' ? t('lines.all') : lineStatusLabel(status)} ({countOf(status)})
            </button>
          ))}
        </div>
      </FilterBar>

      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={5} /></div>
      ) : isError || !lines ? (
        <LoadError onRetry={() => void refetch()} className="m-4" />
      ) : (
        <StatementLineViews lines={shown} actions={actions}
          emptyMessage={statusFilter === 'ALL' ? t('lines.empty') : t('lines.emptyForStatus')} />
      )}

      {actions.matching && (
        <MatchLinePicker pumpId={pumpId} line={actions.matching} isSaving={actions.isMatching} error={actions.dialogError}
          onMatch={actions.match} onClose={actions.closeDialogs} />
      )}
      {actions.ignoring && (
        <RejectReasonDialog
          title={t('ignore.title')}
          subtitle={t('ignore.subtitle', { date: formatIstDate(actions.ignoring.txnDate), amount: formatCurrency(lineAmount(actions.ignoring).amount) })}
          help={t('ignore.help')}
          missingReasonMessage={t('ignore.missingReason')}
          confirmLabel={t('ignore.confirm')}
          pendingLabel={t('ignore.saving')}
          isPending={actions.isIgnoring}
          error={actions.dialogError}
          onConfirm={actions.ignore}
          onClose={actions.closeDialogs}
        />
      )}
    </PageSection>
  )
}
