import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Lock, Printer, Unlock } from 'lucide-react'
import { monthCloseApi } from '../../api/monthCloseApi'
import type { MonthCloseDetail } from '../../api/monthCloseApi'
import { LoadingButton } from '../../components/LoadingButton'
import { RejectReasonDialog } from '../../components/RejectReasonDialog'
import { StickyActions } from '../../components/layout/StickyActions'
import { printWhenFontsReady } from '../../i18n/fonts'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'

interface Props {
  pumpId: number
  detail: MonthCloseDetail
  /** Exception reasons typed so far, by date. */
  reasons: Record<string, string>
  canReopen: boolean
}

/** Close the month (once it is over and every day is approved or has a reason), re-open it (owner) and print it. */
export function MonthCloseActions({ pumpId, detail, reasons, canReopen }: Props) {
  const { t } = useTranslation('dayclose')
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [error, setError] = useState<string | null>(null)
  const [reopening, setReopening] = useState(false)
  const refresh = () => qc.invalidateQueries({ queryKey: ['monthClose', pumpId] })

  const exceptions = detail.days
    .filter(day => day.statusCode !== 'APPROVED')
    .map(day => ({ date: day.date, reason: (reasons[day.date] ?? '').trim() }))
  const missingReasons = exceptions.filter(exception => exception.reason === '').length
  const isClosed = detail.status === 'CLOSED'

  const close = useMutation({
    mutationFn: () => monthCloseApi.close(pumpId, detail.month, exceptions),
    onSuccess: () => { void refresh(); setError(null); addToast(t('month.closed'), 'success') },
    onError: (err) => setError(parseApiError(err, t('month.closeFailed'))),
  })
  const reopen = useMutation({
    mutationFn: (reason: string) => monthCloseApi.reopen(pumpId, detail.month, reason),
    onSuccess: () => { void refresh(); setReopening(false); addToast(t('month.reopened'), 'success') },
    onError: (err) => setError(parseApiError(err, t('month.reopenFailed'))),
  })

  return (
    <StickyActions className="space-y-2 print:hidden">
      {error && <p role="alert" className="ui-alert ui-alert-danger text-sm">{error}</p>}
      {!isClosed && !detail.monthOver && <p className="text-sm text-ink-3">{t('month.notOver')}</p>}
      {!isClosed && detail.monthOver && missingReasons > 0 && (
        <p className="text-sm text-warn">{t('month.reasonsNeeded', { count: missingReasons })}</p>
      )}
      <div className="grid grid-cols-1 gap-2 sm:flex">
        {!isClosed && (
          <LoadingButton type="button" onClick={() => close.mutate()} isLoading={close.isPending} loadingLabel={t('month.closing')}
            disabled={!detail.monthOver || missingReasons > 0} className="ui-btn-primary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Lock size={16} aria-hidden="true" />{t('month.close')}
          </LoadingButton>
        )}
        {isClosed && canReopen && (
          <button type="button" onClick={() => setReopening(true)} className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Unlock size={16} aria-hidden="true" />{t('month.reopen')}
          </button>
        )}
        {detail.summary && (
          <button type="button" onClick={() => { void printWhenFontsReady() }}
            className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Printer size={16} aria-hidden="true" />{t('detail.print')}
          </button>
        )}
      </div>
      {reopening && (
        <RejectReasonDialog title={t('month.reopenTitle', { month: detail.month })} subtitle={t('month.reopenSubtitle')}
          confirmLabel={t('month.reopen')} pendingLabel={t('month.reopening')} isPending={reopen.isPending} error={error}
          onConfirm={(reason) => reopen.mutate(reason)} onClose={() => setReopening(false)} />
      )}
    </StickyActions>
  )
}
