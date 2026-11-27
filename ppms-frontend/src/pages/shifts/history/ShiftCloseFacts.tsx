import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { FUEL_UNIT } from '../../../constants/fuel'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency } from '../../../utils/format'
import { formatIstDateTime } from '../../../utils/date'
import { pricedPartsText } from '../components/pricedPartsText'
import { InvestigationHistory } from '../components/InvestigationHistory'

/**
 * What else happened at and after the close: litres priced at two prices, a difference kept as rounding, stock the
 * tanks did not hold, cash already recovered, and every investigation note.
 */
export function ShiftCloseFacts({ shift }: { shift: Shift }) {
  const { t } = useTranslation(['shifts', 'common'])
  const nozzleNumberById = new Map((shift.nozzles ?? []).map((nozzle) => [nozzle.id, nozzle.nozzleNumber]))
  const pricedReadings = (shift.fuelReadings ?? []).filter((reading) => (reading.priceSegments?.length ?? 0) > 1)
  const remaining = (shift.discrepancyAmount ?? 0) - (shift.cashRecoveryAmount ?? 0)

  const notes = [
    ...pricedReadings.map((reading) => ({
      key: `price-${reading.nozzleId}`,
      text: t('detail.pricedInParts', {
        nozzle: t('close.nozzleLine', { number: nozzleNumberById.get(reading.nozzleId) ?? '?', fuel: codeLabel(reading.fuelType) }),
        parts: pricedPartsText((reading.priceSegments ?? []).map((segment) => ({
          units: segment.unitsSold ?? 0, pricePerUnit: segment.pricePerUnit,
        })), FUEL_UNIT[reading.fuelType] ?? 'L'),
      }),
      tone: 'text-ink-2',
    })),
    shift.roundingAdjustment != null && {
      key: 'rounding',
      text: t('detail.rounding', { amount: formatCurrency(Math.abs(shift.roundingAdjustment)) }),
      tone: 'text-ink-2',
    },
    shift.stockShortfall && { key: 'stock', text: t('detail.stockShortfall'), tone: 'text-warn' },
    shift.cashRecoveryAmount != null && {
      key: 'recovery',
      text: remaining > 0
        ? t('detail.partlyRecovered', { amount: formatCurrency(shift.cashRecoveryAmount), remaining: formatCurrency(remaining) })
        : t('detail.recovered', { amount: formatCurrency(shift.cashRecoveryAmount) }),
      tone: 'text-ink-2',
    },
    shift.closedAt && shift.actualEndTime && shift.closedAt !== shift.actualEndTime && {
      key: 'closed-at',
      text: t('detail.closedAt', { at: formatIstDateTime(shift.closedAt) }),
      tone: 'text-ink-3',
    },
  ].filter((note): note is { key: string; text: string; tone: string } => Boolean(note))

  if (notes.length === 0 && !shift.investigationHistory?.length) return null

  return (
    <section className="space-y-2">
      {notes.map((note) => <p key={note.key} className={`${note.tone} tabular-nums`}>{note.text}</p>)}
      <InvestigationHistory notes={shift.investigationHistory} />
    </section>
  )
}
