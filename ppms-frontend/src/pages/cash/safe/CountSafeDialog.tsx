import { useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { cashApi } from '../../../api/cashApi'
import type { RecordSafeCountRequest, SafeSummary } from '../../../api/cashApi'
import { Modal } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { MoneyText } from '../../../components/AmountText'
import { StillNeeded } from '../../../components/StillNeeded'
import { CashCountField } from '../../../components/cash/CashCountField'
import { EMPTY_COUNTS, toDenominations } from '../../../components/cash/denominations'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { parseApiError } from '../../../utils/apiError'
import { roundToPaise } from '../../../utils/money'
import { formatCurrency } from '../../../utils/format'
import { addDays, formatIstDate, istDateString } from '../../../utils/date'
import { useToastStore } from '../../../store/toastStore'
import { SafeCountDayField } from './SafeCountDayField'
import { countDayOf, countTimingOf, shortTime } from './safeCountDate'
import type { CountDay } from './safeCountDate'

interface Props {
  pumpId: number
  summary: SafeSummary
  /** Owners and admins may count for any open earlier day, with a reason. */
  canBackDate?: boolean
  /** The business day to start on, e.g. the day the Day Close checklist is closing. */
  initialDate?: string | null
  onClose: () => void
}

/**
 * Counts the safe, by note or as one total, against the ledger balance; a big difference notifies the owner. A count for
 * an earlier day is compared with the balance at the end of that day.
 */
export function CountSafeDialog({ pumpId, summary, canBackDate = false, initialDate, onClose }: Props) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [amount, setAmount] = useState('')
  const [counts, setCounts] = useState<DenominationCounts | null>(EMPTY_COUNTS)
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [today] = useState(istDateString)
  const [day, setDay] = useState<CountDay>(() => countDayOf(initialDate, today))
  const [earlierDate, setEarlierDate] = useState(() => (countDayOf(initialDate, today) === 'earlier' && initialDate) || '')
  const [reason, setReason] = useState('')

  const date = day === 'today' ? today : day === 'yesterday' ? addDays(today, -1) : earlierDate
  const timing = countTimingOf(date || today, today, summary.yesterdayCountOpen)
  const isPastDay = date !== '' && timing !== 'ON_THE_DAY'
  const allowed = timing !== 'BACK_DATED' || canBackDate
  const needsReason = timing === 'BACK_DATED' && reason.trim() === ''

  const dayBalance = useQuery({
    queryKey: ['safeExpected', pumpId, date],
    queryFn: () => cashApi.getSafeExpectedBalance(pumpId, date),
    enabled: isPastDay && allowed,
  })
  const expected = isPastDay ? dayBalance.data?.balance ?? null : summary.balance

  const mutation = useMutation({
    mutationFn: (request: RecordSafeCountRequest) => cashApi.recordSafeCount(pumpId, request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['safe', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['safeCounts', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['dayClosePreview', pumpId] })
      addToast(t('safe.count.saved'), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('safe.count.failed'))),
  })

  const counted = amount.trim() === '' ? null : Number(amount)
  const isAmountValid = counted !== null && Number.isFinite(counted) && counted >= 0
  const difference = isAmountValid && expected !== null ? roundToPaise(counted - expected) : null
  const beyondTolerance = difference !== null && Math.abs(difference) > summary.countTolerance
  const stillNeeded = [
    ...(isAmountValid ? [] : [t('safe.count.needAmount')]),
    ...(date === '' ? [t('safe.count.needDate')] : []),
    ...(allowed && date !== '' && needsReason ? [t('safe.count.needReason')] : []),
  ]
  const isValid = isAmountValid && allowed && stillNeeded.length === 0

  const submit = () => {
    if (!isValid) return
    setError(null)
    mutation.mutate({
      countedAmount: counted,
      denominations: counts ? toDenominations(counts) : undefined,
      notes: notes.trim() || undefined,
      countForDate: timing === 'ON_THE_DAY' ? undefined : date,
      backDateReason: timing === 'BACK_DATED' ? reason.trim() : undefined,
    })
  }

  return (
    <Modal
      title={t('safe.count.title')}
      subtitle={isPastDay ? t('safe.count.subtitleFor', { date: formatIstDate(date) }) : t('safe.count.subtitle')}
      tone="info"
      size="md-wide"
      fullScreenOnPhone
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <StillNeeded id={stillNeededId} items={stillNeeded} />
          <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
            {t('common:actions.cancel')}
          </button>
          <LoadingButton type="button" onClick={submit} disabled={!isValid} isLoading={mutation.isPending}
            aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
            loadingLabel={t('safe.count.saving')} className="ui-btn-primary min-h-11">
            {t('safe.count.save')}
          </LoadingButton>
        </>
      }
    >
      <SafeCountDayField today={today} day={day} onDayChange={setDay} earlierDate={earlierDate}
        onEarlierDateChange={setEarlierDate} date={date} timing={timing} canBackDate={canBackDate}
        cutoffTime={shortTime(summary.countCutoffTime)} reason={reason} onReasonChange={setReason} />

      <CashCountField label={t('safe.count.amountLabel')} amount={amount} onAmountChange={setAmount}
        counts={counts} onCountsChange={setCounts} />

      <LiveFigures
        label={t('safe.count.figuresLabel')}
        figures={[
          {
            label: isPastDay ? t('safe.count.expectedEndOfDay', { date: formatIstDate(date) }) : t('safe.count.expected'),
            value: <MoneyText value={expected} />,
          },
          { label: t('safe.count.counted'), value: <MoneyText value={isAmountValid ? counted : null} /> },
          {
            label: t('safe.count.difference'),
            value: <MoneyText value={difference} showSign tone={difference === null || difference === 0 ? undefined : difference < 0 ? 'bad' : 'warn'} />,
            isResult: true,
          },
        ]}
      />
      {dayBalance.isError && <p role="alert" className="ui-alert ui-alert-danger text-sm">{t('safe.count.expectedLoadFailed')}</p>}
      {beyondTolerance && (
        <p className="ui-alert ui-alert-warning text-sm">{t('safe.count.notifyOwner', { amount: formatCurrency(summary.countTolerance) })}</p>
      )}

      <FormField label={<>{t('safe.count.notes')} <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span></>}>
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={2} maxLength={500} className="resize-none" />
      </FormField>

      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
