import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../../api/shiftApi'
import type { AmendShiftRequest } from '../../../api/shiftApi'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { FormField } from '../../../components/FormField'
import { LiveFigures } from '../../../components/LiveFigures'
import { LoadingButton } from '../../../components/LoadingButton'
import { Modal } from '../../../components/Modal'
import { NumericInput } from '../../../components/NumericInput'
import { codeLabel } from '../../../constants/labels'
import type { Shift } from '../../../types/shift'
import { parseApiError } from '../../../utils/apiError'
import { formatCurrency } from '../../../utils/format'
import { amendedFigures, currentFigures, draftOf, invalidFields } from './amendFigures'
import type { AmendDraft } from './amendFigures'

type AmountField = 'cashCollected' | 'upiCollected' | 'cardCollected' | 'fleetCardCollected'

function BeforeAfter({ before, after }: { before: ReactNode; after: ReactNode }) {
  return <span className="inline-flex flex-wrap items-center justify-end gap-1">{before}<span aria-hidden="true">→</span>{after}</span>
}

/** Corrects a closed shift's closing readings and collections; the server works the shift out again and keeps a history. */
export function AmendShiftModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  const qc = useQueryClient()
  const [draft, setDraft] = useState<AmendDraft>(() => draftOf(shift))
  const [reason, setReason] = useState('')
  const [showProblems, setShowProblems] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (request: AmendShiftRequest) => shiftApi.amendShift(shift.pumpId, shift.id, request),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shiftHistory', shift.pumpId] })
      qc.invalidateQueries({ queryKey: ['shift', shift.pumpId, shift.id] })
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('amend.failed'))),
  })

  const before = currentFigures(shift)
  const after = amendedFigures(shift, draft)
  const problems = invalidFields(shift, draft)
  const nozzleNumberOf = (nozzleId: number) => shift.nozzles.find((nozzle) => nozzle.id === nozzleId)?.nozzleNumber ?? nozzleId

  const setAmount = (field: AmountField, value: string) => setDraft((current) => ({ ...current, [field]: value }))
  const setReading = (nozzleId: number, value: string) =>
    setDraft((current) => ({ ...current, endReadings: { ...current.endReadings, [nozzleId]: value } }))

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    if (problems.length > 0 || !reason.trim()) {
      setShowProblems(true)
      return
    }
    mutation.mutate({
      fuelReadings: shift.fuelReadings.map((reading) => ({ nozzleId: reading.nozzleId, endReading: Number(draft.endReadings[reading.nozzleId]) })),
      cashCollected: Number(draft.cashCollected),
      upiCollected: Number(draft.upiCollected),
      cardCollected: Number(draft.cardCollected),
      fleetCardCollected: Number(draft.fleetCardCollected),
      reason: reason.trim(),
    })
  }

  const fieldError = (name: string) => (showProblems && problems.includes(name) ? t('amend.enterNumber') : undefined)
  const amountFields: { field: AmountField; label: string; help?: string }[] = [
    { field: 'cashCollected', label: t('amend.bagCash'), help: shift.cashDropped ? t('amend.bagCashHelp', { amount: formatCurrency(shift.cashDropped) }) : undefined },
    { field: 'upiCollected', label: t('common:paymentMethods.UPI') },
    { field: 'cardCollected', label: t('common:paymentMethods.CARD') },
    { field: 'fleetCardCollected', label: t('common:paymentMethods.FLEET_CARD') },
  ]

  return (
    <Modal
      title={t('amend.title', { id: shift.id })}
      subtitle={t('amend.subtitle', { operator: shift.operatorName, date: shift.shiftDate })}
      tone="warning"
      size="lg"
      onClose={onClose}
      canClose={!mutation.isPending}
      onSubmit={submit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
          <LoadingButton type="submit" isLoading={mutation.isPending} loadingLabel={t('common:actions.saving')} className="ui-btn-primary">
            {t('amend.save')}
          </LoadingButton>
        </>
      }
    >
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {shift.fuelReadings.map((reading) => (
          <FormField key={reading.nozzleId} error={fieldError(`reading-${reading.nozzleId}`)}
            label={t('amend.closingReading', { number: nozzleNumberOf(reading.nozzleId), fuel: codeLabel(reading.fuelType) })}
            help={t('amend.openingReading', { reading: reading.startReading })}>
            <NumericInput value={draft.endReadings[reading.nozzleId] ?? ''} min={0} step="any"
              onChange={(event) => setReading(reading.nozzleId, event.target.value)} />
          </FormField>
        ))}
        {amountFields.map(({ field, label, help }) => (
          <FormField key={field} label={label} help={help} error={fieldError(field)}>
            <NumericInput value={draft[field]} min={0} step="any" unit="₹" onChange={(event) => setAmount(field, event.target.value)} />
          </FormField>
        ))}
      </div>

      <LiveFigures
        label={t('amend.figures')}
        figures={[
          { label: t('amend.litresSold'), value: <BeforeAfter before={<LitresText value={before.litresSold} />} after={<LitresText value={after.litresSold} />} /> },
          { label: t('amend.amountDue'), value: <BeforeAfter before={<MoneyText value={before.amountDue} />} after={<MoneyText value={after.amountDue} />} /> },
          { label: t('amend.totalCollected'), value: <BeforeAfter before={<MoneyText value={before.totalCollected} />} after={<MoneyText value={after.totalCollected} />} /> },
          {
            label: t('amend.difference'),
            isResult: true,
            value: <BeforeAfter
              before={<MoneyText value={before.difference} showSign />}
              after={<MoneyText value={after.difference} showSign tone={after.difference < 0 ? 'bad' : after.difference > 0 ? 'warn' : 'ok'} />} />,
          },
        ]}
      />

      <FormField label={t('amend.reason')} error={showProblems && !reason.trim() ? t('amend.reasonMissing') : undefined}>
        <textarea value={reason} onChange={(event) => setReason(event.target.value)} rows={2} maxLength={500}
          aria-required="true" className="resize-none" />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
