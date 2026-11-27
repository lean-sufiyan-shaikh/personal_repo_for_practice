import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { DayCloseDetail, DayClosePreview } from '../../../api/dayCloseApi'
import { LoadingButton } from '../../../components/LoadingButton'
import { StillNeeded } from '../../../components/StillNeeded'
import { StickyActions } from '../../../components/layout/StickyActions'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { formatIstDate } from '../../../utils/date'
import { checkText, isLocked } from '../dayCloseText'
import { useRefreshDayCloses } from '../useDayCloses'

interface Props {
  pumpId: number
  preview: DayClosePreview
  canApprove: boolean
  onClosed: (detail: DayCloseDetail) => void
}

/** Close the day, or close and approve it in one step for an owner or admin; disabled until every blocking check is done. */
export function CloseDayActions({ pumpId, preview, canApprove, onClosed }: Props) {
  const { t } = useTranslation('dayclose')
  const stillNeededId = useId()
  const { addToast } = useToastStore()
  const refresh = useRefreshDayCloses(pumpId)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (approve: boolean) => dayCloseApi.close(pumpId, preview.businessDate, approve),
    onSuccess: (detail) => {
      refresh()
      addToast(t(detail.status === 'APPROVED' ? 'close.closedAndApproved' : 'close.closed', { date: formatIstDate(detail.businessDate) }), 'success')
      onClosed(detail)
    },
    onError: (err: unknown) => setError(parseApiError(err, t('close.failed'))),
  })

  if (isLocked(preview.status)) return null

  const stillNeeded = preview.checks.filter((item) => item.blocking && !item.done).map(checkText)
  const disabled = !preview.canClose
  const close = (approve: boolean) => {
    setError(null)
    mutation.mutate(approve)
  }

  return (
    <StickyActions className="space-y-2">
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
      <StillNeeded id={stillNeededId} items={stillNeeded} />
      <div className="grid grid-cols-1 gap-2 sm:flex sm:justify-end">
        {canApprove && (
          <LoadingButton type="button" onClick={() => close(true)} disabled={disabled}
            isLoading={mutation.isPending && mutation.variables === true}
            aria-describedby={disabled ? stillNeededId : undefined}
            loadingLabel={t('close.closing')} className="ui-btn-secondary min-h-11">
            {t('close.closeAndApprove')}
          </LoadingButton>
        )}
        <LoadingButton type="button" onClick={() => close(false)} disabled={disabled}
          isLoading={mutation.isPending && mutation.variables === false}
          aria-describedby={disabled ? stillNeededId : undefined}
          loadingLabel={t('close.closing')} className="ui-btn-primary min-h-11">
          {t('close.closeDay', { date: formatIstDate(preview.businessDate) })}
        </LoadingButton>
      </div>
    </StickyActions>
  )
}
