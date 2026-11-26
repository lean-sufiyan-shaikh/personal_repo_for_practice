import { AlertTriangle, CheckCircle2, Info } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { formatDecimal, formatLitres, formatWithSign } from '../../../utils/format'
import type { ReceiptResult } from './receiptCheck'

interface Props {
  result: ReceiptResult | null
  invoiceLitres: number
  tolerancePercent: number
  densityDifference: number | null
  densityFlagged: boolean
  densityTolerance: number
}

type OutcomeKey = 'receipt.match' | 'receipt.shortBeyond' | 'receipt.shortWithin' | 'receipt.overBeyond' | 'receipt.overWithin'

function outcome(result: ReceiptResult): { tone: StatusTone; key: OutcomeKey } {
  if (result.kind === 'MATCH') return { tone: 'ok', key: 'receipt.match' }
  if (result.kind === 'SHORT') return result.beyondTolerance ? { tone: 'bad', key: 'receipt.shortBeyond' } : { tone: 'ok', key: 'receipt.shortWithin' }
  return result.beyondTolerance ? { tone: 'warn', key: 'receipt.overBeyond' } : { tone: 'ok', key: 'receipt.overWithin' }
}

function ReceiptOutcome({ result, invoiceLitres, tolerance }: { result: ReceiptResult; invoiceLitres: number; tolerance: string }) {
  const { t } = useTranslation('inventory')
  const { tone, key } = outcome(result)
  return (
    <>
      <p className="font-semibold text-ink">
        {t('receipt.received', { received: formatLitres(result.receivedLitres), invoiced: formatLitres(invoiceLitres) })}
      </p>
      <StatusBadge tone={tone} wrap icon={tone === 'ok' ? CheckCircle2 : AlertTriangle}
        label={t(key, {
          litres: formatLitres(Math.abs(result.varianceLitres)),
          percent: formatDecimal(Math.abs(result.variancePercent)),
          tolerance,
        })} />
      {result.soldBetweenLitres > 0 && (
        <p className="text-xs text-ink-3">{t('receipt.soldBetween', { litres: formatLitres(result.soldBetweenLitres) })}</p>
      )}
    </>
  )
}

/** What the dips and meter readings say reached the tank, against the invoice and the pump's tolerances. */
export function ReceiptResultCard({ result, invoiceLitres, tolerancePercent, densityDifference, densityFlagged, densityTolerance }: Props) {
  const { t } = useTranslation('inventory')
  const tolerance = formatDecimal(tolerancePercent)
  const densityLimit = formatDecimal(densityTolerance, 1)

  return (
    <div aria-live="polite" className="ui-card ui-card-muted space-y-2 p-3 text-sm">
      {result === null
        ? <p className="text-ink-3">{t('receipt.incomplete')}</p>
        : <ReceiptOutcome result={result} invoiceLitres={invoiceLitres} tolerance={tolerance} />}
      {densityDifference !== null && (
        <StatusBadge tone={densityFlagged ? 'warn' : 'ok'} wrap icon={densityFlagged ? AlertTriangle : Info}
          label={`${t('receipt.densityDifference', { difference: formatWithSign(densityDifference, v => formatDecimal(v, 1)) })} · ${
            densityFlagged ? t('receipt.densityFlag', { tolerance: densityLimit }) : t('receipt.densityOk', { tolerance: densityLimit })}`} />
      )}
    </div>
  )
}
