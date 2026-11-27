import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pumpApi } from '../../../api/pumpApi'
import { formatCurrency } from '../../../utils/format'
import { formatNumberIn } from '../../../i18n/format'

interface UnbilledLitresNoteProps {
  pumpId: number
  fuelType: string
  unit: string
  /** How far the meter moves forward; the note shows only for a forward move. */
  forwardLitres: number
  needsApproval: boolean
}

/**
 * A forward meter move means fuel left the nozzle outside any shift. The note says how much, what it is worth at
 * today's price, and whether it waits for an Owner or Admin or is taken from tank stock at once.
 */
export function UnbilledLitresNote({ pumpId, fuelType, unit, forwardLitres, needsApproval }: UnbilledLitresNoteProps) {
  const { t } = useTranslation('setup')
  const { data: prices = [] } = useQuery({
    queryKey: ['fuelPrices', pumpId],
    queryFn: () => pumpApi.getCurrentPrices(pumpId),
  })
  const price = prices.find(entry => entry.fuelType === fuelType)?.pricePerUnit
  const params = {
    litres: `${formatNumberIn(forwardLitres, { maximumFractionDigits: 3 })} ${unit}`,
    value: price === undefined ? '—' : formatCurrency(Math.round(forwardLitres * price * 100) / 100),
  }

  return (
    <p role="note" className="ui-alert ui-alert-warning text-xs">
      {needsApproval ? t('nozzles.reading.unbilledNeedsApproval', params) : t('nozzles.reading.unbilledDrawn', params)}
    </p>
  )
}
