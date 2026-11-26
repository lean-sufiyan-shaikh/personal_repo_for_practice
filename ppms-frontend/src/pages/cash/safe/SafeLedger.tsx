import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { SafeLedgerEntry } from '../../../api/cashApi'
import { MoneyText } from '../../../components/AmountText'
import { EmptyState } from '../../../components/EmptyState'
import { LoadError } from '../../../components/LoadError'
import { Pagination } from '../../../components/Pagination'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import { FilterBar } from '../../../components/layout/FilterBar'
import { PageSection } from '../../../components/layout/PageSection'
import { EVENT_BADGES, fmtDate } from '../cashFormat'
import { DateRangeInputs } from './DateRangeInputs'

function LedgerRow({ entry }: { entry: SafeLedgerEntry }) {
  const { t } = useTranslation('cash')
  return (
    <li className="ui-record-row flex-wrap gap-y-1">
      <div className="flex min-w-0 items-center gap-3 ui-record-row__main">
        <StatusBadge {...EVENT_BADGES[entry.eventType]} label={t(`eventTypes.${entry.eventType}`)} className="flex-shrink-0" />
        <div className="min-w-0">
          <p className="text-sm text-ink">{entry.description}</p>
          <p className="text-xs text-ink-3">{fmtDate(entry.eventDate)}</p>
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

/** Every movement of money in or out of the safe, newest first, with the balance right after each. */
export function SafeLedger({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('cash')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['safeLedger', pumpId, from, to, page, pageSize],
    queryFn: () => cashApi.getSafeLedger(pumpId, page, pageSize, { from: from || undefined, to: to || undefined }),
    placeholderData: keepPreviousData,
  })
  const hasRange = Boolean(from || to)

  return (
    <PageSection title={t('safe.ledger.title')} flush>
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
        <EmptyState icon="transactions" title={hasRange ? t('safe.ledger.emptyRange') : t('safe.ledger.empty')} />
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
