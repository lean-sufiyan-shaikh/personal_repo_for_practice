import { useState } from 'react'
import type { ReactNode } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ShortageClaim, ShortageClaimStatus } from '../../../api/inventoryApi'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { LoadError } from '../../../components/LoadError'
import { Pagination } from '../../../components/Pagination'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { SkeletonRows } from '../../../components/Skeleton'
import { FilterBar } from '../../../components/layout/FilterBar'
import { PageSection } from '../../../components/layout/PageSection'
import { codeLabel } from '../../../constants/labels'
import { fmtDate } from '../inventoryFormat'
import { ClaimDetailPanel } from './ClaimDetailPanel'
import { ClaimStatusBadge } from './ClaimStatusBadge'

const STATUSES: ShortageClaimStatus[] = ['RAISED', 'SETTLED', 'REJECTED']

function Total({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="ui-card px-4 py-3">
      <dt className="text-xs text-ink-3">{label}</dt>
      <dd className="mt-1 text-base font-semibold text-ink">{children}</dd>
    </div>
  )
}

/** Shortage claims raised from short deliveries, with what is still open and what suppliers have credited. */
export function ClaimsTab({ pumpId, canResolve }: { pumpId: number; canResolve: boolean }) {
  const { t } = useTranslation(['inventory', 'common'])
  const [status, setStatus] = useState<ShortageClaimStatus | ''>('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const [openClaimId, setOpenClaimId] = useState<number | null>(null)

  const { data, isLoading, isLoadingError, refetch } = useQuery({
    queryKey: ['shortageClaims', pumpId, status, page, pageSize],
    queryFn: () => inventoryApi.getShortageClaims(pumpId, status || undefined, page, pageSize),
    placeholderData: keepPreviousData,
  })

  const columns: DataTableColumn<ShortageClaim>[] = [
    { key: 'date', header: t('claims.columns.delivered'), cell: c => fmtDate(c.deliveryDate) },
    { key: 'invoice', header: t('claims.columns.invoice'), cell: c => <span className="font-mono text-xs font-semibold">{c.invoiceReference}</span> },
    { key: 'tank', header: t('claims.columns.tank'), cell: c => `${c.tankIdentifier} · ${codeLabel(c.fuelType)}` },
    { key: 'short', header: t('claims.columns.short'), numeric: true, cell: c => <LitresText value={c.shortLitres} /> },
    { key: 'amount', header: t('claims.claimAmount'), numeric: true, cell: c => <MoneyText value={c.claimAmount} className="font-semibold" /> },
    { key: 'status', header: t('claims.columns.status'), cell: c => <ClaimStatusBadge status={c.status} /> },
    {
      key: 'open', header: <span className="sr-only">{t('claims.columns.actions')}</span>, align: 'right',
      cell: c => (
        <button type="button" onClick={() => setOpenClaimId(c.id)} aria-haspopup="dialog"
          aria-label={t('claims.openNamed', { invoice: c.invoiceReference, tank: c.tankIdentifier })}
          className="ui-btn ui-btn-secondary ui-btn-compact">
          {t('claims.open')}
        </button>
      ),
    },
  ]

  return (
    <div className="space-y-(--density-gap)">
      {data && (
        <dl aria-label={t('claims.totalsLabel')} className="grid grid-cols-1 gap-(--density-gap) sm:grid-cols-3">
          <Total label={t('claims.openTotal', { count: data.totals.openCount })}><MoneyText value={data.totals.openAmount} /></Total>
          <Total label={t('claims.settledTotal', { count: data.totals.settledCount })}><MoneyText value={data.totals.settledAmount} /></Total>
          <Total label={t('claims.rejectedTotal')}>{data.totals.rejectedCount}</Total>
        </dl>
      )}

      <PageSection title={t('claims.title')} description={t('claims.description')} flush>
        <FilterBar label={t('claims.filterTitle')} hasActiveFilters={status !== ''} onClearFilters={() => { setStatus(''); setPage(0) }}>
          <div className="w-44">
            <SearchableSelect label={t('claims.filterByStatus')}
              value={status}
              onChange={v => { setStatus(v as ShortageClaimStatus | ''); setPage(0) }}
              options={[
                { value: '', label: t('claims.allStatuses') },
                ...STATUSES.map(s => ({ value: s, label: t(`claims.status.${s}`) })),
              ]}
              placeholder={t('claims.filterByStatus')}
            />
          </div>
        </FilterBar>

        {isLoading ? (
          <div className="p-(--density-card-pad)"><SkeletonRows count={3} /></div>
        ) : isLoadingError || !data ? (
          <LoadError onRetry={() => void refetch()} className="m-4" />
        ) : (
          <>
            <DataTable caption={t('claims.title')} captionHidden columns={columns} rows={data.claims.content}
              rowKey={c => c.id} cardsOnPhone
              empty={<p className="p-(--density-card-pad) text-sm text-ink-3">{status ? t('claims.noMatch') : t('claims.empty')}</p>} />
            <div className="px-(--density-card-pad)">
              <Pagination data={data.claims} onPageChange={setPage}
                onPageSizeChange={s => { setPageSize(s); setPage(0) }} pageSizeOptions={[10, 20, 50]} />
            </div>
          </>
        )}
      </PageSection>

      {openClaimId !== null && (
        <ClaimDetailPanel pumpId={pumpId} claimId={openClaimId} canResolve={canResolve} onClose={() => setOpenClaimId(null)} />
      )}
    </div>
  )
}
