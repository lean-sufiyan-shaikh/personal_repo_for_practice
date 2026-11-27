import { useTranslation } from 'react-i18next'
import type { ReminderDue } from '../../../api/collectionApi'
import { PageSection } from '../../../components/layout/PageSection'
import { ReminderCard } from './ReminderCard'

interface ToRemindListProps {
  pumpId: number
  reminders: ReminderDue[]
  canRemind: boolean
}

/** Today's reminders, longest overdue first. */
export function ToRemindList({ pumpId, reminders, canRemind }: ToRemindListProps) {
  const { t } = useTranslation('collections')
  return (
    <PageSection title={t('remind.title')} titleMeta={<span className="text-sm text-ink-3">{reminders.length}</span>}>
      {reminders.length === 0 ? (
        <p className="ui-empty">{t('remind.empty')}</p>
      ) : (
        <ul className="grid gap-3 lg:grid-cols-2">
          {reminders.map((reminder) => (
            <ReminderCard key={reminder.clientId} pumpId={pumpId} reminder={reminder} canRemind={canRemind} />
          ))}
        </ul>
      )}
    </PageSection>
  )
}
