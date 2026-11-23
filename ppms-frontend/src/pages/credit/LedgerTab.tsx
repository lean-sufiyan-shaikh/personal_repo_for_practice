import { useState, useDeferredValue } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../api/creditApi'
import type { CreditClient } from '../../api/creditApi'
import { SkeletonRows } from '../../components/Skeleton'
import { Reveal } from '../../components/Reveal'
import { EmptyState } from '../../components/EmptyState'
import { IconButton } from '../../components/IconButton'
import { SetCreditLimitModal } from './modals/SetCreditLimitModal'
import { GenerateAllInvoicesModal } from './modals/GenerateAllInvoicesModal'
import { formatCurrency } from '../../utils/format'
import { CreditAgingTable } from './CreditAgingTable'
import {
  AGING_FILTERS, DEFAULT_SORT, agingFilterOf, agingSortOf, matchesAgingFilter, sortAgingRows,
} from './creditAging'
import type { AgingFilter, AgingSortKey } from './creditAging'

interface LedgerTabProps {
  pumpId: number | null
  clients: CreditClient[]
  isLoading: boolean
  hasError: boolean
  canAdjustLimit: boolean
  canIssueInvoices: boolean
  onSelectClient: (client: Pick<CreditClient, 'id'>) => void
}

function SummaryFigure({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="ui-dashboard-module px-4 py-3">
      <p className="text-xs font-semibold uppercase tracking-wide text-ink-3">{label}</p>
      <p className={`mt-1 text-lg font-bold tabular-nums ${tone ?? 'text-ink'}`}>{value}</p>
    </div>
  )
}

export function LedgerTab({ pumpId, clients, isLoading, hasError, canAdjustLimit, canIssueInvoices, onSelectClient }: LedgerTabProps) {
  const { t } = useTranslation('credit')
  const [limitClientId, setLimitClientId] = useState<number | null>(null)
  const [showGenerateAllModal, setShowGenerateAllModal] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const deferredSearch = useDeferredValue(searchQuery)
  const [searchParams, setSearchParams] = useSearchParams()
  const filter = agingFilterOf(searchParams.get('filter'))
  const sort = agingSortOf(searchParams.get('sort'), searchParams.get('dir'))

  const { data: aging, isLoading: agingLoading, isError: agingFailed } = useQuery({
    queryKey: ['credit-ledger', pumpId, 'aging'],
    queryFn: () => creditApi.getAging(pumpId!),
    enabled: pumpId != null,
  })

  const updateParams = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(searchParams)
    Object.entries(changes).forEach(([key, value]) => (value === null ? next.delete(key) : next.set(key, value)))
    setSearchParams(next, { replace: true })
  }
  const setFilter = (value: AgingFilter) => updateParams({ filter: value === 'all' ? null : value })
  const setSort = (key: AgingSortKey) => {
    const direction = key === sort.key ? (sort.direction === 'desc' ? 'asc' : 'desc') : (key === 'name' ? 'asc' : 'desc')
    const isDefault = key === DEFAULT_SORT.key && direction === DEFAULT_SORT.direction
    updateParams({ sort: isDefault ? null : key, dir: isDefault ? null : direction })
  }

  const query = deferredSearch.trim().toLowerCase()
  const allRows = aging?.clients ?? []
  const rows = sortAgingRows(
    allRows.filter(row => matchesAgingFilter(row, filter)
      && (!query || row.name.toLowerCase().includes(query) || (row.phone ?? '').includes(query))),
    sort.key, sort.direction)
  const countByFilter: Record<AgingFilter, number> = {
    'all': allRows.length,
    'overdue': aging?.summary.overdueCount ?? 0,
    'over-limit': aging?.summary.overLimitCount ?? 0,
    'pending-approval': aging?.summary.pendingApprovalCount ?? 0,
  }
  const limitClient = clients.find(client => client.id === limitClientId)

  return (
    <>
      {limitClient && pumpId && (
        <SetCreditLimitModal client={limitClient} pumpId={pumpId} onClose={() => setLimitClientId(null)} />
      )}
      {showGenerateAllModal && pumpId && (
        <GenerateAllInvoicesModal pumpId={pumpId} onClose={() => setShowGenerateAllModal(false)} />
      )}
      {canIssueInvoices && pumpId && clients.length > 0 && (
        <div className="flex justify-end">
          <button type="button" onClick={() => setShowGenerateAllModal(true)} className="ui-btn ui-btn-secondary">
            {t('aging.generateAllInvoices')}
          </button>
        </div>
      )}

      {aging && allRows.length > 0 && (
        <Reveal delay={110}>
          <section aria-label={t('aging.summaryLabel')} className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <SummaryFigure label={t('aging.totalOutstanding')} value={formatCurrency(aging.summary.totalOutstanding)} />
            <SummaryFigure label={t('aging.filters.overdue')} value={formatCurrency(aging.summary.overdueAmount)}
              tone={aging.summary.overdueAmount > 0 ? 'text-bad' : undefined} />
            <SummaryFigure label={t('aging.olderThan60')} value={formatCurrency(aging.summary.overSixtyAmount)}
              tone={aging.summary.overSixtyAmount > 0 ? 'text-warn' : undefined} />
          </section>
        </Reveal>
      )}

      <Reveal delay={120}>
      <div className="flex flex-wrap items-center gap-3">
        <div role="group" aria-label={t('aging.showClients')} className="ui-tabbar w-fit">
          {AGING_FILTERS.map(option => (
            <button key={option.value} type="button" aria-pressed={filter === option.value}
              onClick={() => setFilter(option.value)}
              className={`ui-tabbar__button ${filter === option.value ? 'ui-tabbar__button--active' : ''}`}>
              {t(`aging.filters.${option.labelKey}`)}{aging ? ` (${countByFilter[option.value]})` : ''}
            </button>
          ))}
        </div>
        <div className="ui-search-shell min-w-60 flex-1">
          <svg className="ui-search-shell__icon" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
          </svg>
          <input aria-label={t('aging.searchLabel')}
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder={t('aging.searchPlaceholder')}
            className="ui-search-shell__input text-sm leading-5"
          />
          {searchQuery && (
            <IconButton
              onClick={() => setSearchQuery('')}
              className="ui-search-shell__clear"
              label={t('aging.clearSearch')}
              icon={<svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>}
            />
          )}
        </div>
      </div>
      </Reveal>

      {(isLoading || agingLoading) && (
        <div className="ui-card px-5 py-4">
          <SkeletonRows count={5} />
        </div>
      )}

      {(hasError || agingFailed) && (
        <div role="alert" className="ui-alert ui-alert-danger text-sm">
          {t('aging.loadFailed')}
        </div>
      )}

      {!isLoading && !agingLoading && aging && (
        allRows.length === 0 ? (
          <div className="ui-card">
            <EmptyState icon="transactions" title={t('aging.noClients')} subtitle={t('aging.noClientsHint')} />
          </div>
        ) : rows.length === 0 ? (
          <div className="ui-card">
            <EmptyState icon="generic"
              title={query ? t('aging.noMatch', { query: deferredSearch.trim() }) : t('aging.noneInView')}
              subtitle={query ? t('aging.noMatchHint') : t('aging.noneInViewHint')} />
          </div>
        ) : (
          <CreditAgingTable
            rows={rows}
            sortKey={sort.key}
            sortDirection={sort.direction}
            canAdjustLimit={canAdjustLimit}
            onSort={setSort}
            onOpenClient={clientId => onSelectClient({ id: clientId })}
            onSetLimit={setLimitClientId}
          />
        )
      )}
    </>
  )
}
