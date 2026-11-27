import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import type { Shift } from '../../../types/shift'
import { CashCountField } from '../../../components/cash/CashCountField'
import { EMPTY_COUNTS, toDenominations } from '../../../components/cash/denominations'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { LoadingButton } from '../../../components/LoadingButton'
import { parseApiError } from '../../../utils/apiError'
import { emptyNum } from '../components/creditRow'
import { PaymentField } from '../close/CloseShiftFields'

interface Props {
  shift: Shift
  onCounted: (shift: Shift) => void
}

/**
 * The collections counted before the expected amount is shown. Once saved the count is locked; the close must use it.
 * Nothing on this form hints at what the shift should have taken.
 */
export function BlindCountForm({ shift, onCounted }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const queryClient = useQueryClient()
  const [cash, setCash] = useState('')
  const [cashCounts, setCashCounts] = useState<DenominationCounts | null>(EMPTY_COUNTS)
  const [upi, setUpi] = useState('')
  const [card, setCard] = useState('')
  const [fleetCard, setFleetCard] = useState('')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => shiftApi.recordBlindCount(shift.pumpId, shift.id, {
      cashCollected: emptyNum(cash),
      ...(cashCounts && { cashDenominations: toDenominations(cashCounts) }),
      upiCollected: emptyNum(upi),
      cardCollected: emptyNum(card),
      fleetCardCollected: emptyNum(fleetCard),
    }),
    onSuccess: (counted) => {
      queryClient.setQueryData(['shift', shift.pumpId, shift.id], counted)
      queryClient.invalidateQueries({ queryKey: ['activeShifts', shift.pumpId] })
      onCounted(counted)
    },
    onError: (err: unknown) => setError(parseApiError(err, t('blindCount.saveFailed'))),
  })

  const anyEntered = [cash, upi, card, fleetCard].some((value) => value !== '')

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-2">{t('blindCount.help')}</p>
      <CashCountField label={t('blindCount.cash')} amount={cash} onAmountChange={setCash} counts={cashCounts}
        onCountsChange={setCashCounts} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <PaymentField label={t('close.payment.upi')} value={upi} onChange={setUpi} />
        <PaymentField label={t('close.payment.card')} value={card} onChange={setCard} />
        <PaymentField label={t('close.payment.fleetCard')} value={fleetCard} onChange={setFleetCard} />
      </div>
      <p className="text-xs text-ink-3">{t('blindCount.lockedNote')}</p>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
      <LoadingButton type="button" onClick={() => { setError(null); mutation.mutate() }} disabled={!anyEntered}
        isLoading={mutation.isPending} loadingLabel={t('blindCount.saving')} className="ui-btn-primary min-h-11">
        {t('blindCount.save')}
      </LoadingButton>
    </div>
  )
}
