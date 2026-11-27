import { useTranslation } from 'react-i18next'
import type { TimelineEvent } from '../../../types/shift'
import { formatIstDateTime } from '../../../utils/date'
import { formatCurrency, formatDecimal } from '../../../utils/format'

/** What happened on the shift, oldest first: operators joining, nozzle swaps, breaks, the blind count and the close. */
export function ShiftTimelineList({ events }: { events: TimelineEvent[] }) {
  const { t } = useTranslation(['shifts', 'common'])
  return (
    <section className="space-y-2" aria-label={t('operations.timeline.title')}>
      <h3 className="ui-title-sm">{t('operations.timeline.title')}</h3>
      <ol className="space-y-2 border-l border-border pl-4">
        {events.map((event, index) => (
          <li key={`${event.type}-${event.at}-${index}`} className="text-sm">
            <span className="block text-xs text-ink-3 tabular-nums">
              {formatIstDateTime(event.at, { hour: '2-digit', minute: '2-digit', hour12: true })}
            </span>
            <span className="text-ink">
              {t(`operations.timeline.events.${event.type}`, {
                name: event.operatorName ?? '—',
                other: event.otherOperatorName ?? '—',
              })}
            </span>
            {event.readings.length > 0 && (
              <span className="block text-xs text-ink-3">
                {event.readings.map((reading) => t('operations.timeline.reading', {
                  number: reading.nozzleNumber ?? '?', reading: formatDecimal(reading.meterReading, 3),
                })).join(' · ')}
              </span>
            )}
            {event.amount != null && (
              <span className="block text-xs text-ink-3">{t('operations.timeline.cash', { amount: formatCurrency(event.amount) })}</span>
            )}
            {event.note && <span className="block text-xs text-ink-3">{event.note}</span>}
          </li>
        ))}
      </ol>
    </section>
  )
}
