import { Link } from 'react-router-dom'
import { CalendarCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../../store/authStore'
import { canAccessPage } from '../../../permissions/permissions'
import { localDateInputValue } from '../../../utils/date'
import { nextDayToClose, useRecentDayCloses } from '../../dayclose/useDayCloses'

/** The day to close next (yesterday, then today) and, for an owner or admin, how many closed days wait for approval. */
export function DayCloseCard({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('dayclose')
  const role = useAuthStore((state) => state.user?.role)
  const { data } = useRecentDayCloses(canAccessPage('dayClose', role) ? pumpId : null)
  if (!data) return null

  const nextDay = nextDayToClose(data.content)
  const waiting = data.content.filter((day) => day.status === 'CLOSED').length
  const canApprove = role === 'OWNER' || role === 'ADMIN'
  const title = nextDay === null
    ? t('card.allClosed')
    : nextDay === localDateInputValue() ? t('card.closeToday') : t('card.closeYesterday')

  return (
    <section aria-label={t('card.label')} className="ui-card flex flex-wrap items-center gap-3 border-l-4 border-l-primary">
      <CalendarCheck size={22} strokeWidth={2} aria-hidden="true" className="shrink-0 text-primary" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-ink">{title}</p>
        <p className="text-xs text-ink-3">
          {canApprove && waiting > 0 ? t('card.waiting', { count: waiting }) : t('card.hint')}
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        {canApprove && waiting > 0 && (
          <Link to="/dashboard/day-close?view=approvals" className="ui-btn ui-btn-secondary min-h-11">{t('card.review')}</Link>
        )}
        {nextDay !== null && (
          <Link to={`/dashboard/day-close?view=close&date=${nextDay}`} className="ui-btn ui-btn-primary min-h-11">{t('card.open')}</Link>
        )}
      </div>
    </section>
  )
}
