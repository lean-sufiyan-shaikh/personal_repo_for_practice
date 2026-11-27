import { useTranslation } from 'react-i18next'
import type { PaymentPromise, PromiseStatus, Reminder } from '../../../api/collectionApi'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { formatIstDate, formatIstDateTime } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

const PROMISE_TONE: Record<PromiseStatus, StatusTone> = { OPEN: 'info', KEPT: 'ok', MISSED: 'bad' }

/** The account's latest promises and reminders, newest first. */
export function FollowUpHistory({ promises, reminders }: { promises: PaymentPromise[]; reminders: Reminder[] }) {
  const { t } = useTranslation('collections')
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <section>
        <h3 className="ui-section-heading">{t('promise.historyTitle')}</h3>
        {promises.length === 0 ? <p className="ui-empty">{t('promise.none')}</p> : (
          <ul className="divide-y divide-border">
            {promises.map((promise) => (
              <li key={promise.id} className="space-y-1 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium text-ink">
                    {t('promise.summary', { amount: formatCurrency(promise.amount), date: formatIstDate(promise.promisedDate) })}
                  </span>
                  <StatusBadge tone={PROMISE_TONE[promise.status]} label={t(`promise.status.${promise.status}`)} />
                  {promise.paidAmount != null && (
                    <span className="text-xs text-ink-3">{t('promise.paid', { amount: formatCurrency(promise.paidAmount) })}</span>
                  )}
                </div>
                <p className="text-xs text-ink-3">
                  {t('promise.recordedBy', { name: promise.recordedByName, date: formatIstDate(promise.recordedOn) })}
                </p>
                {promise.note && <p className="break-words text-sm text-ink-2">{promise.note}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section>
        <h3 className="ui-section-heading">{t('followUp.remindersTitle')}</h3>
        {reminders.length === 0 ? <p className="ui-empty">{t('followUp.noReminders')}</p> : (
          <ul className="divide-y divide-border">
            {reminders.map((reminder) => (
              <li key={reminder.id} className="space-y-1 py-2">
                <p className="text-sm text-ink">
                  {t('followUp.reminderRow', {
                    method: t(`methods.${reminder.method}`), name: reminder.remindedByName, days: reminder.daysOverdue,
                  })}
                </p>
                <p className="text-xs text-ink-3">{formatIstDateTime(reminder.remindedAt)}</p>
                {reminder.note && <p className="break-words text-sm text-ink-2">{reminder.note}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
