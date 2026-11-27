import { useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { paymentSettlementApi } from '../../api/paymentSettlementApi'
import { MoneyText } from '../../components/AmountText'
import { EmptyState } from '../../components/EmptyState'
import { LoadError } from '../../components/LoadError'
import { Pagination } from '../../components/Pagination'
import { SkeletonRows } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { PageSection } from '../../components/layout/PageSection'
import { codeLabel } from '../../constants/labels'
import { fmtDate, typeLabel } from './settlementFormat'

/** Chargebacks and refunds the bank took back, newest first. */
export function ChargebackList({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('settlements')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['chargebacks', pumpId, page, pageSize],
    queryFn: () => paymentSettlementApi.listChargebacks(pumpId, page, pageSize),
    placeholderData: keepPreviousData,
  })

  return (
    <PageSection title={t('chargeback.listTitle')} flush>
      {isLoading ? (
        <div className="px-5 py-4"><SkeletonRows count={2} /></div>
      ) : isError || !data ? (
        <LoadError onRetry={() => void refetch()} className="m-4" />
      ) : data.content.length === 0 ? (
        <EmptyState icon="transactions" title={t('chargeback.empty')} />
      ) : (
        <>
          <ul className="ui-record-list">
            {data.content.map(c => (
              <li key={c.id} className="ui-record-row flex-wrap gap-y-1">
                <div className="flex min-w-0 items-center gap-3 ui-record-row__main">
                  <StatusBadge tone={c.kind === 'CHARGEBACK' ? 'bad' : 'warn'} label={codeLabel(c.kind)} className="flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-sm text-ink break-words">{c.reason}</p>
                    <p className="text-xs text-ink-3">
                      {typeLabel(c.paymentType)} · {fmtDate(c.chargebackDate)}
                      {c.settlementId ? ` · ${t('chargeback.settlementRef', { id: c.settlementId })}` : ''}
                      {c.collectionDate ? ` · ${t('chargeback.collectedOn', { date: fmtDate(c.collectionDate) })}` : ''}
                      {c.reference ? ` · ${c.reference}` : ''}
                    </p>
                  </div>
                </div>
                <MoneyText value={-c.amount} showSign tone="bad" className="ml-auto text-sm font-semibold" />
              </li>
            ))}
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
