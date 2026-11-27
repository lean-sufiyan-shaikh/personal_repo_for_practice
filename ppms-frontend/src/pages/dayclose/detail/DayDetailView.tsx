import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { useTranslation } from 'react-i18next'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { DayCloseDetail } from '../../../api/dayCloseApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import { formatIstDate, formatIstDateTime } from '../../../utils/date'
import { dayStatusBadge } from '../dayCloseText'
import { DaySummaryView } from '../summary/DaySummaryView'
import { DayDetailActions } from './DayDetailActions'
import { CommentThread, VersionList } from './DayHistory'

interface Props {
  pumpId: number
  pumpName: string | undefined
  date: string
  version: number | null
  isOwnerOrAdmin: boolean
  onShowVersion: (version: number) => void
  /** Shown instead when the day was never closed. */
  notClosed: ReactNode
}

function DayHeading({ detail, pumpName }: { detail: DayCloseDetail; pumpName: string | undefined }) {
  const { t } = useTranslation('dayclose')
  return (
    <div className="ui-card space-y-2 print:border-0 print:p-0">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-lg font-semibold text-ink">
          <span className="hidden print:inline">{t('print.title', { pump: pumpName ?? '' })} · </span>
          {formatIstDate(detail.businessDate, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}
        </h2>
        <StatusBadge {...dayStatusBadge(detail.status)} />
      </div>
      <p className="text-sm text-ink-2">
        {t('detail.closedBy', { name: detail.closedByName, time: formatIstDateTime(detail.closedAt), version: detail.version })}
      </p>
      {detail.approvedByName && detail.approvedAt && (
        <p className="text-sm text-ok">{t('detail.approvedBy', { name: detail.approvedByName, time: formatIstDateTime(detail.approvedAt) })}</p>
      )}
      {detail.balanceSheetId !== null && (
        <p className="text-sm print:hidden">
          <Link to={`/dashboard/balance-sheets?sheet=${detail.balanceSheetId}`} className="ui-link">{t('detail.balanceSheet')}</Link>
          {detail.balanceSheetStale && <span className="ml-2 text-warn">{t('detail.balanceSheetStale')}</span>}
        </p>
      )}
    </div>
  )
}

/** A closed day: who closed and approved it, its stored summary (or an earlier version), the conversation and the actions. */
export function DayDetailView({ pumpId, pumpName, date, version, isOwnerOrAdmin, onShowVersion, notClosed }: Props) {
  const { t } = useTranslation('dayclose')
  const detailQuery = useQuery({
    queryKey: ['dayClose', pumpId, date],
    queryFn: () => dayCloseApi.getDetail(pumpId, date),
    retry: false,
  })
  const detail = detailQuery.data
  const olderVersion = detail && version !== null && version !== detail.version ? version : null
  const versionQuery = useQuery({
    queryKey: ['dayClose', pumpId, date, 'version', olderVersion],
    queryFn: () => dayCloseApi.getVersion(pumpId, date, olderVersion!),
    enabled: olderVersion !== null,
  })

  if (detailQuery.isLoading) return <div className="ui-card"><SkeletonRows count={5} /></div>
  if (isAxiosError(detailQuery.error) && detailQuery.error.response?.status === 404) return <>{notClosed}</>
  if (detailQuery.isError || !detail) return <LoadError message={t('detail.loadFailed')} onRetry={() => void detailQuery.refetch()} />

  const summary = olderVersion !== null ? versionQuery.data : detail.summary
  return (
    <div className="space-y-(--density-gap) print:space-y-2">
      <DayHeading detail={detail} pumpName={pumpName} />
      {olderVersion !== null && (
        <p className="ui-alert ui-alert-info text-sm print:hidden">{t('versions.olderNotice', { version: olderVersion, current: detail.version })}</p>
      )}
      {summary ? <DaySummaryView summary={summary} /> : versionQuery.isError
        ? <LoadError message={t('versions.loadFailed')} onRetry={() => void versionQuery.refetch()} />
        : <div className="ui-card"><SkeletonRows count={4} /></div>}
      <CommentThread comments={detail.comments} />
      <VersionList versions={detail.versions} current={detail.version} shown={olderVersion ?? detail.version} onShow={onShowVersion} />
      <DayDetailActions pumpId={pumpId} detail={detail} isOwnerOrAdmin={isOwnerOrAdmin} />
    </div>
  )
}
