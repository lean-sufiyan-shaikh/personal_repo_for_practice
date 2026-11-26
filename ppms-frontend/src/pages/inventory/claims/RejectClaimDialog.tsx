import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ShortageClaim } from '../../../api/inventoryApi'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { Modal } from '../../../components/Modal'
import { StillNeeded } from '../../../components/StillNeeded'
import { parseApiError } from '../../../utils/apiError'
import { formatCurrency } from '../../../utils/format'

interface Props {
  pumpId: number
  claim: ShortageClaim
  onDone: (claim: ShortageClaim) => void
  onClose: () => void
}

export function RejectClaimDialog({ pumpId, claim, onDone, onClose }: Props) {
  const { t } = useTranslation(['inventory', 'common'])
  const stillNeededId = useId()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => inventoryApi.rejectShortageClaim(pumpId, claim.id, reason.trim()),
    onSuccess: onDone,
    onError: (err) => setError(parseApiError(err, t('rejectClaim.failed'))),
  })
  const stillNeeded = reason.trim() ? [] : [t('rejectClaim.reasonNeeded')]

  return (
    <Modal
      title={t('rejectClaim.title')}
      subtitle={t('rejectClaim.subtitle', { invoice: claim.invoiceReference, amount: formatCurrency(claim.claimAmount) })}
      tone="danger"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={(e) => { e.preventDefault(); setError(null); mutation.mutate() }}
      bodyClassName="space-y-4"
      footerClassName="flex-wrap"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
        <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('rejectClaim.rejecting')}
          disabled={stillNeeded.length > 0} aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          className="ui-btn-danger">
          {t('rejectClaim.confirm')}
        </LoadingButton>
      </>}
    >
      <p className="text-sm text-ink-2">{t('rejectClaim.explanation')}</p>
      <FormField label={t('rejectClaim.reason')}>
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} maxLength={500}
          className="resize-none" data-autofocus />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
