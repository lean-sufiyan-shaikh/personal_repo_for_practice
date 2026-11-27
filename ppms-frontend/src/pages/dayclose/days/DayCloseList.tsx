import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { MessageCircle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { DayCloseListItem, DayCloseStatus, FlagColour } from '../../../api/dayCloseApi'
import { MoneyText } from '../../../components/AmountText'
import { LoadError } from '../../../components/LoadError'
import { Pagination } from '../../../components/Pagination'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import { formatIstDate } from '../../../utils/date'
import { dayStatusBadge, flagBadge } from '../dayCloseText'

const DOT_BY_COLOUR: Record<FlagColour, string> = { GREEN: 'bg-ok', AMBER: 'bg-warn', RED: 'bg-bad' }

function FlagDot({ label, colour }: { label: string; colour: FlagColour }) {
  return (
    <span className="inline-flex items-center gap-1 text-xs text-ink-2">
      <span aria-hidden="true" className={`h-2.5 w-2.5 rounded-full ${DOT_BY_COLOUR[colour]}`} />
      <span>{label}</span>
      <span className="sr-only">{flagBadge(colour).label}</span>
    </span>
  )
}

function DayCard({ day }: { day: DayCloseListItem }) {
  const { t } = useTranslation('dayclose')
  return (
    <li>
      <Link to={`?date=${day.businessDate}`} className="block space-y-2 px-4 py-3 hover:bg-surface-sunken">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-base font-semibold text-ink">{formatIstDate(day.businessDate, { weekday: 'short', day: 'numeric', month: 'short' })}</span>
          <StatusBadge {...dayStatusBadge(day.status)} />
          <StatusBadge {...flagBadge(day.overallFlag)} />
          {day.commentCount > 0 && (
            <span className="inline-flex items-center gap-1 text-xs text-ink-3">
              <MessageCircle size={14} aria-hidden="true" />{t('list.comments', { count: day.commentCount })}
            </span>
          )}
        </div>
        <div className="flex flex-wrap gap-x-4 gap-y-1">
          <FlagDot label={t('sales.short')} colour={day.salesFlag} />
          <FlagDot label={t('cash.short')} colour={day.cashFlag} />
          <FlagDot label={t('stock.short')} colour={day.stockFlag} />
          <FlagDot label={t('credit.short')} colour={day.creditFlag} />
        </div>
        <p className="text-sm text-ink-2">
          {t('list.revenue')} <MoneyText value={day.fuelRevenue} /> · {t('list.margin')} <MoneyText value={day.grossMargin} />
        </p>
        <p className="text-xs text-ink-3">{t('list.closedBy', { name: day.closedByName })}</p>
      </Link>
    </li>
  )
}

/** Closed days newest first, as cards that open the day's summary; status narrows it, e.g. to days waiting for approval. */
export function DayCloseList({ pumpId, status }: { pumpId: number; status?: DayCloseStatus }) {
  const { t } = useTranslation('dayclose')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(10)
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['dayCloses', pumpId, status ?? 'all', page, size],
    queryFn: () => dayCloseApi.list(pumpId, page, size, status),
  })

  if (isLoading) return <div className="ui-card"><SkeletonRows count={4} /></div>
  if (isError || !data) return <LoadError message={t('list.loadFailed')} onRetry={() => void refetch()} />
  if (data.content.length === 0) {
    return <div className="ui-card text-center text-sm text-ink-3">{status === 'CLOSED' ? t('list.noneWaiting') : t('list.none')}</div>
  }
  return (
    <div className="ui-card overflow-hidden p-0">
      <ul aria-label={status === 'CLOSED' ? t('tabs.approvals') : t('tabs.days')} className="divide-y divide-border">
        {data.content.map((day) => <DayCard key={day.businessDate} day={day} />)}
      </ul>
      <Pagination data={data} onPageChange={setPage} onPageSizeChange={(next) => { setSize(next); setPage(0) }} />
    </div>
  )
}
