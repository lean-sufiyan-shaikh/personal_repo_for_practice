import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { notificationApi } from '../../api/notificationApi'
import type { Notification, NotificationType } from '../../api/notificationApi'
import { Pagination } from '../../components/Pagination'
import { SkeletonRows } from '../../components/Skeleton'
import { StatusBadge } from '../../components/StatusBadge'
import { NotificationTypeIcon } from '../../components/NotificationTypeIcon'
import { NOTIFICATION_TYPES, notificationTypeLabel, openableHref } from '../../components/notificationLinks'
import { notificationText } from '../../components/notificationGroups'
import { useNow } from '../../hooks/useNow'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { parseApiError } from '../../utils/apiError'
import { formatIstDateTime, withReadableDates } from '../../utils/date'
import { relativeTime } from '../../utils/relativeTime'

function NotificationRow({ notification, now, onOpen }: { notification: Notification; now: number; onOpen: (id: number) => void }) {
  const { t } = useTranslation(['notifications', 'common'])
  const role = useAuthStore((state) => state.user?.role)
  const href = openableHref(notification.target, role)
  const { title, message } = notificationText(notification)
  const body = (
    <div className="flex items-start gap-3">
      <NotificationTypeIcon type={notification.type} />
      <div className="min-w-0 flex-1">
        <p className={`text-sm ${notification.read ? 'font-medium text-ink-2' : 'font-semibold text-ink'}`}>
          {title}
        </p>
        <p className="mt-1 text-sm text-ink-3">{withReadableDates(message)}</p>
        <p className="mt-1 flex flex-wrap gap-x-2 text-xs text-ink-3">
          <time dateTime={notification.createdAt} title={formatIstDateTime(notification.createdAt)}>
            {relativeTime(notification.createdAt, new Date(now))}
          </time>
          <span aria-hidden="true">·</span>
          <span>{notificationTypeLabel(notification.type)}</span>
        </p>
      </div>
      <StatusBadge tone={notification.read ? 'neutral' : 'info'} label={notification.read ? t('common:notifications.read') : t('common:notifications.unread')} className="flex-shrink-0" />
    </div>
  )
  return (
    <li>
      {href ? (
        <Link to={href} onClick={() => { if (!notification.read) onOpen(notification.id) }} className="block px-5 py-4 hover:bg-bg">
          {body}
        </Link>
      ) : (
        <div className="px-5 py-4">{body}</div>
      )}
    </li>
  )
}

export default function NotificationsPage() {
  const { t, i18n } = useTranslation(['notifications', 'common'])
  const { selectedPumpId: pumpId } = usePumpStore()
  const qc = useQueryClient()
  const now = useNow(60_000)
  const [type, setType] = useState<NotificationType | ''>('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)

  const { data, isLoading, error } = useQuery({
    queryKey: ['notification-history', pumpId, type, page, pageSize],
    queryFn: () => notificationApi.getHistory(pumpId!, page, pageSize, type || undefined),
    enabled: !!pumpId,
    placeholderData: keepPreviousData,
  })

  const markRead = useMutation({
    mutationFn: (id: number) => notificationApi.markRead(pumpId!, [id]),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['notifications', pumpId] })
      qc.invalidateQueries({ queryKey: ['notification-history', pumpId] })
    },
  })

  const typeOptions = NOTIFICATION_TYPES
    .map((value) => [value, notificationTypeLabel(value)] as const)
    .sort(([, a], [, b]) => a.localeCompare(b, i18n.language))

  if (!pumpId) {
    return <div className="ui-page ui-page--narrow"><div className="ui-empty">{t('page.noPump')}</div></div>
  }

  return (
    <div className="ui-page ui-page--narrow space-y-5">
      <div className="ui-page-header">
        <div>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle">{t('page.subtitle')}</p>
        </div>
      </div>

      <div className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <p className="ui-toolbar-title">{t('page.allCount', { total: data?.totalElements ?? '…' })}</p>
          <label className="flex w-full items-center gap-2 text-sm sm:w-auto">
            <span className="text-ink-3">{t('page.typeFilter')}</span>
            <select
              value={type}
              onChange={(e) => { setType(e.target.value as NotificationType | ''); setPage(0) }}
              className="text-sm"
            >
              <option value="">{t('page.allTypes')}</option>
              {typeOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </label>
        </div>

        {isLoading ? (
          <div className="px-5 py-4"><SkeletonRows count={5} /></div>
        ) : error ? (
          <p role="alert" className="ui-alert ui-alert-danger m-4 text-sm">{parseApiError(error, t('page.loadFailed'))}</p>
        ) : !data || data.content.length === 0 ? (
          <p className="ui-empty px-5 py-6">{type ? t('page.emptyForType') : t('page.empty')}</p>
        ) : (
          <ul className="divide-y divide-border">
            {data.content.map((notification) => (
              <NotificationRow key={notification.id} notification={notification} now={now} onOpen={(id) => markRead.mutate(id)} />
            ))}
          </ul>
        )}

        {data && data.totalElements > 0 && (
          <div className="px-5 pb-4">
            <Pagination
              data={data}
              onPageChange={setPage}
              onPageSizeChange={(size) => { setPageSize(size); setPage(0) }}
              pageSizeOptions={[20, 50, 100]}
            />
          </div>
        )}
      </div>
    </div>
  )
}
