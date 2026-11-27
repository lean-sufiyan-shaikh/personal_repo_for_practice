import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { collectionApi } from '../../../api/collectionApi'
import type { ReminderDue } from '../../../api/collectionApi'
import { LoadError } from '../../../components/LoadError'
import { SkeletonRows } from '../../../components/Skeleton'
import { UpiQrCode } from '../../../components/UpiQrCode'
import { formatIstDate } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'
import { CancelPromiseModal } from './CancelPromiseModal'
import { FollowUpHistory } from './FollowUpHistory'
import { OverrideHoldModal } from './OverrideHoldModal'
import { PromiseModal } from './PromiseModal'
import { ReminderCard } from './ReminderCard'

interface ClientFollowUpTabProps {
  pumpId: number
  clientId: number
  clientName: string
  phone: string | null
  /** Records reminders, and records, changes and cancels promises (managers and above). */
  canRemind: boolean
  /** Overrides a credit hold (owners and admins). */
  canOverrideHold: boolean
}

/** One account's collection follow-up: hold, days overdue, the reminder due, promises and what was done so far. */
export function ClientFollowUpTab({ pumpId, clientId, clientName, phone, canRemind, canOverrideHold }: ClientFollowUpTabProps) {
  const { t } = useTranslation('collections')
  const [showPromise, setShowPromise] = useState(false)
  const [changingPromise, setChangingPromise] = useState(false)
  const [cancellingPromise, setCancellingPromise] = useState(false)
  const [showOverride, setShowOverride] = useState(false)
  const { data: followUp, isLoading, isError, refetch } = useQuery({
    queryKey: ['collections', pumpId, 'client', clientId],
    queryFn: () => collectionApi.getClientFollowUp(pumpId, clientId),
  })

  if (isLoading) return <SkeletonRows count={4} />
  if (isError || !followUp) return <LoadError onRetry={() => refetch()} message={t('followUp.loadFailed')} />

  const openPromise = followUp.promises.find((promise) => promise.status === 'OPEN')
  const dueReminder: ReminderDue | null = followUp.reminderDue && followUp.reachedStage != null && followUp.overdueSince
    ? {
        clientId: followUp.clientId, clientName, phone, outstanding: followUp.outstanding,
        daysOverdue: followUp.daysOverdue, overdueSince: followUp.overdueSince, stageDays: followUp.reachedStage,
        onHold: followUp.onHold, lastRemindedAt: null, lastReminderMethod: null, upiPayLink: followUp.upiPayLink,
      }
    : null

  return (
    <div className="space-y-4">
      {followUp.onHold && followUp.creditHoldDays != null && (
        <div role="status" className="ui-alert ui-alert-danger flex flex-wrap items-center justify-between gap-3 text-sm">
          <span className="min-w-0 flex-1">
            {t('hold.onHold', { days: followUp.daysOverdue, holdDays: followUp.creditHoldDays })}
          </span>
          {canOverrideHold && (
            <button type="button" onClick={() => setShowOverride(true)} className="ui-btn ui-btn-secondary">
              {t('hold.override')}
            </button>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-0.5 text-sm">
          <p className={followUp.daysOverdue > 0 ? 'font-medium text-bad' : 'text-ink-2'}>
            {followUp.daysOverdue > 0 && followUp.overdueSince
              ? t('followUp.overdue', { days: followUp.daysOverdue, date: formatIstDate(followUp.overdueSince) })
              : t('followUp.notOverdue')}
          </p>
          {!followUp.onHold && followUp.daysOverdue > 0 && followUp.creditHoldDays != null && (
            <p className="text-ink-3">{t('hold.willHold', { holdDays: followUp.creditHoldDays })}</p>
          )}
          {openPromise && (
            <p className="text-info">
              {t('promise.openUntil', { amount: formatCurrency(openPromise.amount), date: formatIstDate(openPromise.promisedDate) })}
            </p>
          )}
          {openPromise && canRemind && (
            <div className="flex flex-wrap gap-2 pt-1">
              <button type="button" onClick={() => setChangingPromise(true)} className="ui-btn ui-btn-secondary">
                {t('promise.change')}
              </button>
              <button type="button" onClick={() => setCancellingPromise(true)} className="ui-btn ui-btn-secondary">
                {t('promise.cancel')}
              </button>
            </div>
          )}
        </div>
        {canRemind && !openPromise && followUp.outstanding > 0 && (
          <button type="button" onClick={() => setShowPromise(true)} className="ui-btn ui-btn-secondary">
            {t('promise.record')}
          </button>
        )}
      </div>

      {dueReminder && (
        <ul>
          <ReminderCard pumpId={pumpId} reminder={dueReminder} canRemind={canRemind} />
        </ul>
      )}

      {followUp.upiPayLink && (
        <div className="flex flex-wrap items-center gap-3">
          <UpiQrCode link={followUp.upiPayLink} size={128}
            label={t('upi.qrLabel', { amount: formatCurrency(followUp.outstanding) })} />
        </div>
      )}

      <FollowUpHistory promises={followUp.promises} reminders={followUp.reminders} />

      {showPromise && (
        <PromiseModal pumpId={pumpId} clientId={followUp.clientId} clientName={clientName}
          outstanding={followUp.outstanding} onClose={() => setShowPromise(false)} />
      )}
      {changingPromise && openPromise && (
        <PromiseModal pumpId={pumpId} clientId={followUp.clientId} clientName={clientName}
          outstanding={followUp.outstanding} promise={openPromise} onClose={() => setChangingPromise(false)} />
      )}
      {cancellingPromise && openPromise && (
        <CancelPromiseModal pumpId={pumpId} clientName={clientName} promise={openPromise}
          onClose={() => setCancellingPromise(false)} />
      )}
      {showOverride && (
        <OverrideHoldModal pumpId={pumpId} clientId={followUp.clientId} clientName={clientName}
          onClose={() => setShowOverride(false)} />
      )}
    </div>
  )
}
