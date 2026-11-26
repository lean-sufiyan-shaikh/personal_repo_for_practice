import { useId, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import { EmptyState } from '../../../components/EmptyState'
import { LoadError } from '../../../components/LoadError'
import { Pagination } from '../../../components/Pagination'
import { SkeletonRows } from '../../../components/Skeleton'
import { FilterBar } from '../../../components/layout/FilterBar'
import { PageSection } from '../../../components/layout/PageSection'
import { formatCurrency } from '../../../utils/format'
import { DateRangeInputs } from './DateRangeInputs'
import { DepositCard } from './DepositCard'

/** Bank deposits out of the safe, filtered by date and bank account, with the count and total of every match. */
export function DepositList({ pumpId, canAttach }: { pumpId: number; canAttach: boolean }) {
  const { t } = useTranslation('cash')
  const accountId = useId()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [bankAccount, setBankAccount] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)

  const filter = { from: from || undefined, to: to || undefined, bankAccount: bankAccount.trim() || undefined }
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['bankDeposits', pumpId, filter, page, pageSize],
    queryFn: () => cashApi.getDeposits(pumpId, page, pageSize, filter),
    placeholderData: keepPreviousData,
  })
  const hasFilters = Boolean(from || to || bankAccount.trim())

  return (
    <PageSection
      title={t('safe.deposits.title')}
      titleMeta={data && t('safe.deposits.summary', { count: data.totalCount, amount: formatCurrency(data.totalAmount) })}
      flush
    >
      <FilterBar label={t('safe.deposits.filtersLabel')} hasActiveFilters={hasFilters}
        onClearFilters={() => { setFrom(''); setTo(''); setBankAccount(''); setPage(0) }}>
        <DateRangeInputs from={from} to={to}
          onFromChange={value => { setFrom(value); setPage(0) }}
          onToChange={value => { setTo(value); setPage(0) }} />
        <div className="flex items-center gap-1.5">
          <label htmlFor={accountId} className="text-xs text-ink-3 whitespace-nowrap">{t('safe.deposits.bankFilter')}</label>
          <input id={accountId} type="search" value={bankAccount} className="text-xs"
            onChange={e => { setBankAccount(e.target.value); setPage(0) }} />
        </div>
      </FilterBar>

      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={3} /></div>
      ) : isError || !data ? (
        <LoadError onRetry={() => void refetch()} className="m-4" />
      ) : data.deposits.content.length === 0 ? (
        <EmptyState icon="transactions" title={hasFilters ? t('safe.deposits.emptyFiltered') : t('safe.deposits.empty')} />
      ) : (
        <>
          <ul className="ui-record-list">
            {data.deposits.content.map(deposit => (
              <DepositCard key={deposit.id} pumpId={pumpId} deposit={deposit} canAttach={canAttach} />
            ))}
          </ul>
          <div className="px-5">
            <Pagination data={data.deposits} onPageChange={setPage}
              onPageSizeChange={size => { setPageSize(size); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
          </div>
        </>
      )}
    </PageSection>
  )
}
