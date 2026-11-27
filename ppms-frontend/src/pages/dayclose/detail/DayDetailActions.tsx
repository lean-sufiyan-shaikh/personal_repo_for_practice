import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Check, MessageCircle, Printer, RotateCcw } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { DayCloseDetail } from '../../../api/dayCloseApi'
import { LoadingButton } from '../../../components/LoadingButton'
import { StickyActions } from '../../../components/layout/StickyActions'
import { printWhenFontsReady } from '../../../i18n/fonts'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { formatIstDate } from '../../../utils/date'
import { isLocked } from '../dayCloseText'
import { useRefreshDayCloses } from '../useDayCloses'
import { DayTextDialog } from './DayTextDialog'

type OpenDialog = 'comment' | 'reopen' | null

interface Props {
  pumpId: number
  detail: DayCloseDetail
  isOwnerOrAdmin: boolean
}

/** Approve, ask a question (or reply), re-open and print, kept at the bottom of a phone screen. */
export function DayDetailActions({ pumpId, detail, isOwnerOrAdmin }: Props) {
  const { t } = useTranslation('dayclose')
  const { addToast } = useToastStore()
  const refresh = useRefreshDayCloses(pumpId)
  const [dialog, setDialog] = useState<OpenDialog>(null)
  const [error, setError] = useState<string | null>(null)
  const date = detail.businessDate
  const day = formatIstDate(date)

  const finish = (message: string) => {
    refresh()
    setDialog(null)
    setError(null)
    addToast(message, 'success')
  }
  const fail = (fallback: string) => (err: unknown) => setError(parseApiError(err, fallback))

  const approve = useMutation({
    mutationFn: () => dayCloseApi.approve(pumpId, date),
    onSuccess: () => finish(t('detail.approved', { date: day })),
    onError: fail(t('detail.approveFailed')),
  })
  const comment = useMutation({
    mutationFn: (message: string) => dayCloseApi.addComment(pumpId, date, message),
    onSuccess: () => finish(t(isOwnerOrAdmin ? 'detail.questionSent' : 'detail.replySent')),
    onError: fail(t('detail.commentFailed')),
  })
  const reopen = useMutation({
    mutationFn: (reason: string) => dayCloseApi.reopen(pumpId, date, reason),
    onSuccess: () => finish(t('detail.reopened', { date: day })),
    onError: fail(t('detail.reopenFailed')),
  })

  const openDialog = (next: OpenDialog) => { setError(null); setDialog(next) }

  return (
    <>
      <StickyActions className="space-y-2 print:hidden">
        {error && dialog === null && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
        <div className="grid grid-cols-2 gap-2 sm:flex sm:flex-wrap sm:justify-end">
          <button type="button" onClick={() => { void printWhenFontsReady() }} className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <Printer size={16} aria-hidden="true" />{t('detail.print')}
          </button>
          <button type="button" onClick={() => openDialog('comment')} className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
            <MessageCircle size={16} aria-hidden="true" />{isOwnerOrAdmin ? t('detail.askQuestion') : t('detail.reply')}
          </button>
          {isOwnerOrAdmin && isLocked(detail.status) && (
            <button type="button" onClick={() => openDialog('reopen')} className="ui-btn ui-btn-secondary min-h-11 inline-flex items-center justify-center gap-1.5">
              <RotateCcw size={16} aria-hidden="true" />{t('detail.reopen')}
            </button>
          )}
          {isOwnerOrAdmin && detail.status === 'CLOSED' && (
            <LoadingButton type="button" onClick={() => { setError(null); approve.mutate() }} isLoading={approve.isPending}
              loadingLabel={t('detail.approving')} className="ui-btn-primary min-h-11 col-span-2 inline-flex items-center justify-center gap-1.5">
              <Check size={16} aria-hidden="true" />{t('detail.approve')}
            </LoadingButton>
          )}
        </div>
      </StickyActions>

      {dialog === 'comment' && (
        <DayTextDialog
          title={isOwnerOrAdmin ? t('comment.questionTitle') : t('comment.replyTitle')}
          subtitle={isOwnerOrAdmin ? t('comment.questionSubtitle', { date: day }) : t('comment.replySubtitle', { date: day })}
          label={t('comment.label')} missingText={t('comment.missing')}
          submitLabel={t('comment.send')} submittingLabel={t('comment.sending')}
          tone="info" maxLength={1000} isPending={comment.isPending} error={error}
          onSubmit={(message) => comment.mutate(message)} onClose={() => setDialog(null)} />
      )}
      {dialog === 'reopen' && (
        <DayTextDialog
          title={t('reopen.title', { date: day })} subtitle={t('reopen.subtitle')}
          label={t('reopen.label')} missingText={t('reopen.missing')}
          submitLabel={t('reopen.submit')} submittingLabel={t('reopen.submitting')}
          tone="warning" maxLength={500} isPending={reopen.isPending} error={error}
          onSubmit={(reason) => reopen.mutate(reason)} onClose={() => setDialog(null)} />
      )}
    </>
  )
}
