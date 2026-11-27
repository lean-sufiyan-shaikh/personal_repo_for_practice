import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { monthCloseApi } from '../../api/monthCloseApi'
import { LoadError } from '../../components/LoadError'
import { SkeletonRows } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { useUrlParam } from '../../components/layout/useUrlParam'
import { canPerformAction } from '../../permissions/permissions'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { MonthDayChecklist } from './MonthDayChecklist'
import { MonthSummaryView } from './MonthSummaryView'
import { MonthCloseActions } from './MonthCloseActions'
import { MonthCloseHistory } from './MonthCloseHistory'
import { previousMonth } from './monthCloseFormat'

const YEAR_MONTH = /^\d{4}-\d{2}$/

/**
 * Month close: ?month=YYYY-MM (last month by default) lists the days not yet approved, takes a reason for each one
 * closed as an exception, closes the month and shows and prints its stored summary; the owner re-opens it.
 */
export default function MonthClosePage() {
  const { t } = useTranslation(['dayclose', 'common'])
  const role = useAuthStore(state => state.user?.role)
  const { selectedPumpId: pumpId } = usePumpStore()
  const monthParam = useUrlParam('month')
  const month = monthParam.value && YEAR_MONTH.test(monthParam.value) ? monthParam.value : previousMonth()
  const [reasonsByMonth, setReasonsByMonth] = useState<Record<string, Record<string, string>>>({})
  const [shownVersion, setShownVersion] = useState<{ month: string; version: number } | null>(null)
  const reasons = reasonsByMonth[month] ?? {}

  const detail = useQuery({
    queryKey: ['monthClose', pumpId, month],
    queryFn: () => monthCloseApi.getDetail(pumpId!, month),
    enabled: pumpId !== null,
  })
  const olderVersion = shownVersion?.month === month && shownVersion.version !== detail.data?.currentVersion
    ? shownVersion.version : null
  const versionSummary = useQuery({
    queryKey: ['monthClose', pumpId, month, 'version', olderVersion],
    queryFn: () => monthCloseApi.getVersion(pumpId!, month, olderVersion!),
    enabled: pumpId !== null && olderVersion !== null,
  })
  const summary = olderVersion !== null ? versionSummary.data : detail.data?.summary
  const data = detail.data

  return (
    <Page>
      <div className="print:hidden">
        <PageHeader title={t('month.title')} description={t('month.subtitle')}
          badges={data?.status && (
            <StatusBadge tone={data.status === 'CLOSED' ? 'ok' : 'warn'}
              label={t(data.status === 'CLOSED' ? 'month.statusClosed' : 'month.statusReopened', { version: data.currentVersion })} />
          )} />
        <label className="mt-2 flex items-center gap-2 text-sm text-ink-2">
          {t('month.pick')}
          <input type="month" value={month} max={previousMonth()} onChange={e => e.target.value && monthParam.set(e.target.value)}
            className="min-h-11" />
        </label>
      </div>
      <p className="hidden print:block text-base font-semibold">{t('month.printTitle', { month })}</p>

      {pumpId === null ? (
        <div className="ui-card text-center text-sm text-ink-3">{t('common:notices.noPump')}</div>
      ) : detail.isLoading ? (
        <div className="ui-card"><SkeletonRows count={4} /></div>
      ) : detail.isError || !data ? (
        <LoadError message={t('month.loadFailed')} onRetry={() => void detail.refetch()} />
      ) : (
        <div className="space-y-(--density-gap)">
          <div className="print:hidden">
            <MonthDayChecklist days={data.days}
              reasons={data.status !== 'CLOSED' && data.monthOver ? reasons : null}
              onReasonChange={(date, reason) => setReasonsByMonth(all => ({ ...all, [month]: { ...reasons, [date]: reason } }))} />
          </div>
          {summary && <MonthSummaryView summary={summary} />}
          {data.versions.length > 0 && (
            <MonthCloseHistory versions={data.versions} shownVersion={olderVersion ?? data.currentVersion}
              onShow={version => setShownVersion({ month, version })} />
          )}
          <MonthCloseActions pumpId={pumpId} detail={data} reasons={reasons}
            canReopen={canPerformAction('monthClose:reopen', role)} />
        </div>
      )}
    </Page>
  )
}
