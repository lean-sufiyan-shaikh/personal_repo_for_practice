import { useId, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { inventoryApi } from '../../../api/inventoryApi'
import type { ShortageClaim } from '../../../api/inventoryApi'
import { MoneyText } from '../../../components/AmountText'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { Modal } from '../../../components/Modal'
import { NumericInput } from '../../../components/NumericInput'
import { StillNeeded } from '../../../components/StillNeeded'
import { FormGrid, FormGridFullRow } from '../../../components/layout/FormGrid'
import { parseApiError } from '../../../utils/apiError'
import { localDateInputValue } from '../../../utils/date'
import { limitDecimals } from '../inventoryFormat'

interface Props {
  pumpId: number
  claim: ShortageClaim
  onDone: (claim: ShortageClaim) => void
  onClose: () => void
}

export function SettleClaimDialog({ pumpId, claim, onDone, onClose }: Props) {
  const { t } = useTranslation(['inventory', 'common'])
  const stillNeededId = useId()
  const today = localDateInputValue()
  const [amount, setAmount] = useState(String(claim.claimAmount))
  const [reference, setReference] = useState('')
  const [date, setDate] = useState(today)
  const [error, setError] = useState<string | null>(null)

  const amountValue = Number(amount)
  const mutation = useMutation({
    mutationFn: () => inventoryApi.settleShortageClaim(pumpId, claim.id, {
      creditNoteAmount: amountValue, creditNoteReference: reference.trim(), creditNoteDate: date,
    }),
    onSuccess: onDone,
    onError: (err) => setError(parseApiError(err, t('settleClaim.failed'))),
  })

  const stillNeeded = [
    ...(amount.trim() && amountValue > 0 ? [] : [t('settleClaim.amountNeeded')]),
    ...(reference.trim() ? [] : [t('settleClaim.referenceNeeded')]),
    ...(!date ? [t('settleClaim.dateNeeded')] : date > today ? [t('settleClaim.dateNotFuture')] : []),
  ]

  return (
    <Modal
      title={t('settleClaim.title')}
      subtitle={t('settleClaim.subtitle', { invoice: claim.invoiceReference })}
      tone="success"
      size="md"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={(e) => { e.preventDefault(); setError(null); mutation.mutate() }}
      bodyClassName="space-y-4"
      footerClassName="flex-wrap"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">{t('common:actions.cancel')}</button>
        <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('settleClaim.settling')}
          disabled={stillNeeded.length > 0} aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          className="ui-btn-primary">
          {t('settleClaim.confirm')}
        </LoadingButton>
      </>}
    >
      <LiveFigures figures={[
        { label: t('claims.claimAmount'), value: <MoneyText value={claim.claimAmount} /> },
        { label: t('settleClaim.creditNote'), value: <MoneyText value={amountValue > 0 ? amountValue : null} />, isResult: true },
      ]} />
      <FormGrid>
        <FormField label={t('settleClaim.amount')}>
          <NumericInput step="0.01" min="0.01" unit="₹" value={amount}
            onChange={(e) => setAmount(limitDecimals(e.target.value, 2))} />
        </FormField>
        <FormField label={t('settleClaim.date')}>
          <input type="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} />
        </FormField>
        <FormGridFullRow>
          <FormField label={t('settleClaim.reference')}>
            <input type="text" value={reference} maxLength={100} onChange={(e) => setReference(e.target.value)} />
          </FormField>
        </FormGridFullRow>
      </FormGrid>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
