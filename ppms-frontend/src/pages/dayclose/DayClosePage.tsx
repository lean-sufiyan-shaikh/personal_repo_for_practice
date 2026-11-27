import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { pumpApi } from '../../api/pumpApi'
import type { DayCloseDetail } from '../../api/dayCloseApi'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { PageTabs } from '../../components/layout/PageTabs'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { localDateInputValue } from '../../utils/date'
import { CloseDayView } from './close/CloseDayView'
import { DayCloseList } from './days/DayCloseList'
import { DayDetailView } from './detail/DayDetailView'
import { nextDayToClose, useRecentDayCloses } from './useDayCloses'

type View = 'close' | 'approvals' | 'days'
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/**
 * Day Close: ?view=close picks a day, shows its checklist and preview and closes it; ?view=approvals and ?view=days list
 * closed days; ?date=… alone opens a closed day's summary (or its close view when it was never closed).
 */
export default function DayClosePage() {
  const { t } = useTranslation(['dayclose', 'common'])
  const role = useAuthStore((state) => state.user?.role)
  const { selectedPumpId: pumpId } = usePumpStore()
  const [searchParams, setSearchParams] = useSearchParams()
  const isOwnerOrAdmin = role === 'OWNER' || role === 'ADMIN'
  const recent = useRecentDayCloses(pumpId)
  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps, staleTime: 5 * 60_000 })

  const dateParam = searchParams.get('date')
  const date = dateParam && ISO_DATE.test(dateParam) ? dateParam : null
  const viewParam = searchParams.get('view')
  const view: View = viewParam === 'close' || viewParam === 'days' || (viewParam === 'approvals' && isOwnerOrAdmin)
    ? viewParam
    : isOwnerOrAdmin ? 'approvals' : 'close'
  const showDetail = date !== null && viewParam !== 'close'
  const versionParam = Number(searchParams.get('version'))
  const version = Number.isInteger(versionParam) && versionParam > 0 ? versionParam : null

  const update = (change: (params: URLSearchParams) => void, replace = false) => setSearchParams((params) => {
    const next = new URLSearchParams(params)
    change(next)
    return next
  }, { replace })

  const closeDate = date ?? nextDayToClose(recent.data?.content ?? []) ?? localDateInputValue()
  const openDetail = (detail: DayCloseDetail) => setSearchParams({ date: detail.businessDate })

  const tabs = [
    { id: 'close', label: t('tabs.close') },
    ...(isOwnerOrAdmin ? [{ id: 'approvals', label: t('tabs.approvals') }] : []),
    { id: 'days', label: t('tabs.days') },
  ]

  const closeView = pumpId !== null && (
    <CloseDayView pumpId={pumpId} date={showDetail && date ? date : closeDate} canApprove={isOwnerOrAdmin}
      onDateChange={(next) => update((params) => { params.set('view', 'close'); params.set('date', next) }, true)}
      onClosed={openDetail} />
  )

  return (
    <Page>
      <div className="print:hidden">
        <PageHeader
          title={t('page.title')}
          description={t('page.subtitle')}
          backLink={showDetail ? { to: `?view=${isOwnerOrAdmin ? 'approvals' : 'days'}`, label: t('page.backToDays') } : undefined}
          tabs={pumpId !== null && !showDetail && (
            <PageTabs label={t('page.views')} tabs={tabs} activeTab={view} param="view" resetParams={['date', 'version']} />
          )}
        />
      </div>

      {pumpId === null ? (
        <div className="ui-card text-center text-sm text-ink-3">{t('common:notices.noPump')}</div>
      ) : showDetail && date ? (
        <DayDetailView pumpId={pumpId} pumpName={pumps.find((pump) => pump.id === pumpId)?.name} date={date}
          version={version} isOwnerOrAdmin={isOwnerOrAdmin} notClosed={closeView}
          onShowVersion={(next) => update((params) => params.set('version', String(next)))} />
      ) : view === 'close' ? (
        closeView
      ) : (
        <DayCloseList key={view} pumpId={pumpId} status={view === 'approvals' ? 'CLOSED' : undefined} />
      )}
    </Page>
  )
}
