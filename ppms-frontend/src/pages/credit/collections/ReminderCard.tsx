import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Copy, Lock, Phone } from 'lucide-react'
import type { ReminderDue, ReminderMethod } from '../../../api/collectionApi'
import { MoneyText } from '../../../components/AmountText'
import { StatusBadge } from '../../../components/StatusBadge'
import { useToastStore } from '../../../store/toastStore'
import { formatIstDate } from '../../../utils/date'
import { MarkRemindedModal } from './MarkRemindedModal'
import { callLink, reminderMessage } from './reminderMessage'

interface ReminderCardProps {
  pumpId: number
  reminder: ReminderDue
  canRemind: boolean
}

/** One account to remind: the ready message with Copy and Call, then Mark reminded to record how it was done. */
export function ReminderCard({ pumpId, reminder, canRemind }: ReminderCardProps) {
  const { t } = useTranslation(['collections', 'common'])
  const { addToast } = useToastStore()
  const [marking, setMarking] = useState(false)
  const [usedMethod, setUsedMethod] = useState<ReminderMethod>('CALL')
  const message = reminderMessage(reminder, pumpId)
  const telLink = callLink(reminder.phone)

  const copyMessage = async () => {
    try {
      await navigator.clipboard.writeText(message)
      setUsedMethod('MESSAGE_COPIED')
      addToast(t('remind.copied'), 'success')
    } catch (error) {
      console.error('Could not copy the reminder message', error)
      addToast(t('remind.copyFailed'), 'error')
    }
  }

  return (
    <li className="ui-card-plain space-y-3 px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="break-words font-semibold text-ink">{reminder.clientName}</p>
          <p className="text-sm text-ink-3">
            <MoneyText value={reminder.outstanding} className="font-medium text-ink" />
            {' · '}{t('dashboard.daysOverdue', { days: reminder.daysOverdue })}
          </p>
        </div>
        <div className="flex flex-wrap gap-1.5">
          <StatusBadge tone="warn" label={t('remind.stage', { days: reminder.stageDays })} />
          {reminder.onHold && <StatusBadge tone="bad" icon={Lock} label={t('remind.onHold')} />}
        </div>
      </div>
      {reminder.lastRemindedAt && reminder.lastReminderMethod && (
        <p className="text-xs text-ink-3">
          {t('remind.lastReminded', { date: formatIstDate(reminder.lastRemindedAt), method: t(`methods.${reminder.lastReminderMethod}`) })}
        </p>
      )}
      <p aria-label={t('remind.messageLabel')}
        className="whitespace-pre-wrap break-words rounded bg-surface-sunken px-3 py-2 text-sm text-ink-2">
        {message}
      </p>
      <div className="flex flex-wrap gap-2">
        <button type="button" onClick={copyMessage} className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5">
          <Copy size={14} aria-hidden="true" />{t('remind.copy')}
        </button>
        {telLink && (
          <a href={telLink} onClick={() => setUsedMethod('CALL')} className="ui-btn ui-btn-secondary inline-flex items-center gap-1.5">
            <Phone size={14} aria-hidden="true" />{t('remind.call')}
          </a>
        )}
        {canRemind && (
          <button type="button" onClick={() => setMarking(true)} className="ui-btn ui-btn-primary">
            {t('remind.markReminded')}
          </button>
        )}
      </div>
      {marking && (
        <MarkRemindedModal pumpId={pumpId} clientId={reminder.clientId} clientName={reminder.clientName}
          initialMethod={usedMethod} onClose={() => setMarking(false)} />
      )}
    </li>
  )
}
