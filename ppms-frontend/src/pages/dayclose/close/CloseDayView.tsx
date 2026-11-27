import { useId } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { DayCloseDetail, DayCloseStatus } from '../../../api/dayCloseApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { StatusBadge } from '../../../components/StatusBadge'
import { PageSection } from '../../../components/layout/PageSection'
import { formatIstDate, localDateInputValue } from '../../../utils/date'
import { dayStatusBadge, isLocked } from '../dayCloseText'
import { DaySummaryView } from '../summary/DaySummaryView'
import { DayChecklist } from './DayChecklist'
import { CloseDayActions } from './CloseDayActions'

interface Props {
  pumpId: number
  date: string
  canApprove: boolean
  onDateChange: (date: string) => void
  onClosed: (detail: DayCloseDetail) => void
}

function StatusBanner({ date, status }: { date: string; status: DayCloseStatus }) {
  const { t } = useTranslation('dayclose')
  return (
    <div className={`ui-alert ${isLocked(status) ? 'ui-alert-info' : 'ui-alert-warning'} flex flex-wrap items-center gap-2 text-sm`}>
      <StatusBadge {...dayStatusBadge(status)} />
      <span className="min-w-0 flex-1">{t(`close.banner.${status}`, { date: formatIstDate(date) })}</span>
      {isLocked(status) && <Link to={`?date=${date}`} className="ui-link">{t('close.openSummary')}</Link>}
    </div>
  )
}

/** Pick a day, see its checklist and a live preview of the owner's summary, then close it. */
export function CloseDayView({ pumpId, date, canApprove, onDateChange, onClosed }: Props) {
  const { t } = useTranslation('dayclose')
  const dateId = useId()
  const { data: preview, isLoading, isError, refetch } = useQuery({
    queryKey: ['dayClosePreview', pumpId, date],
    queryFn: () => dayCloseApi.getPreview(pumpId, date),
  })

  return (
    <div className="space-y-(--density-gap)">
      <div className="ui-card flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor={dateId} className="ui-label">{t('close.dayLabel')}</label>
          <input id={dateId} type="date" value={date} max={localDateInputValue()}
            onChange={(event) => { if (event.target.value) onDateChange(event.target.value) }} />
        </div>
        <p className="min-w-0 flex-1 text-sm text-ink-3">{t('close.dayHint')}</p>
      </div>

      {isLoading ? (
        <div className="ui-card"><SkeletonRows count={4} /></div>
      ) : isError || !preview ? (
        <LoadError message={t('close.loadFailed')} onRetry={() => void refetch()} />
      ) : (
        <>
          {preview.status && <StatusBanner date={date} status={preview.status} />}
          {!isLocked(preview.status) && <DayChecklist checks={preview.checks} />}
          <PageSection title={t('close.previewTitle')} description={t('close.previewDescription')}>
            <DaySummaryView summary={preview.summary} />
          </PageSection>
          <CloseDayActions pumpId={pumpId} preview={preview} canApprove={canApprove} onClosed={onClosed} />
        </>
      )}
    </div>
  )
}
