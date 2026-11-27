import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ArrowDown, ArrowUp, Undo2 } from 'lucide-react'
import { pettyCashApi } from '../../../api/pettyCashApi'
import type { PettyCashEntry, PettyCashEntryType } from '../../../api/pettyCashApi'
import { MoneyText } from '../../../components/AmountText'
import { EmptyState } from '../../../components/EmptyState'
import { LoadError } from '../../../components/LoadError'
import { Pagination } from '../../../components/Pagination'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusBadgeSpec } from '../../../components/statusBadges'
import { FilterBar } from '../../../components/layout/FilterBar'
import { PageSection } from '../../../components/layout/PageSection'
import { fmtDate } from '../cashFormat'
import { DateRangeInputs } from '../safe/DateRangeInputs'

const ENTRY_BADGES: Record<PettyCashEntryType, Omit<StatusBadgeSpec, 'label'>> = {
  TOP_UP: { tone: 'ok', icon: ArrowDown },
  EXPENSE: { tone: 'bad', icon: ArrowUp },
  REVERSAL: { tone: 'neutral', icon: Undo2 },
}

function LedgerRow({ entry }: { entry: PettyCashEntry }) {
  const { t } = useTranslation('cash')
  return (
    <li className="ui-record-row flex-wrap gap-y-1">
      <div className="flex min-w-0 items-center gap-3 ui-record-row__main">
        <StatusBadge {...ENTRY_BADGES[entry.entryType]} label={t(`petty.entryTypes.${entry.entryType}`)} className="flex-shrink-0" />
        <div className="min-w-0">
          <p className={`text-sm text-ink ${entry.reversed ? 'line-through' : ''}`}>{entry.description}</p>
          <p className="text-xs text-ink-3">{fmtDate(entry.entryDate)}</p>
        </div>
      </div>
      <div className="ml-auto text-right">
        <MoneyText value={entry.amount} showSign tone={entry.amount < 0 ? 'bad' : 'ok'} className="block text-sm font-semibold" />
        <span className="text-xs text-ink-3">
          {t('safe.ledger.balanceAfter')} <MoneyText value={entry.balanceAfter} className="text-ink-2" />
        </span>
      </div>
    </li>
  )
}

/** Every movement of the petty cash box, newest first, with the balance right after each. */
export function PettyCashLedger({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('cash')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['pettyCash', pumpId, 'ledger', from, to, page, pageSize],
    queryFn: () => pettyCashApi.getLedger(pumpId, page, pageSize, { from: from || undefined, to: to || undefined }),
    placeholderData: keepPreviousData,
  })
  const hasRange = Boolean(from || to)

  return (
    <PageSection title={t('petty.ledgerTitle')} flush>
      <FilterBar label={t('safe.ledger.filtersLabel')} hasActiveFilters={hasRange}
        onClearFilters={() => { setFrom(''); setTo(''); setPage(0) }}>
        <DateRangeInputs from={from} to={to}
          onFromChange={value => { setFrom(value); setPage(0) }}
          onToChange={value => { setTo(value); setPage(0) }} />
      </FilterBar>

      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={4} /></div>
      ) : isError || !data ? (
        <LoadError onRetry={() => void refetch()} className="m-4" />
      ) : data.content.length === 0 ? (
        <EmptyState icon="transactions" title={hasRange ? t('safe.ledger.emptyRange') : t('petty.ledgerEmpty')} />
      ) : (
        <>
          <ul className="ui-record-list">
            {data.content.map(entry => <LedgerRow key={entry.id} entry={entry} />)}
          </ul>
          <div className="px-5">
            <Pagination data={data} onPageChange={setPage}
              onPageSizeChange={size => { setPageSize(size); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
          </div>
        </>
      )}
    </PageSection>
  )
}
