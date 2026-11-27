import { AlertTriangle, Check, Eye, HelpCircle, type LucideIcon } from 'lucide-react'
import type { RadarBand, RadarEvidence, ScoreReason } from '../../api/radarApi'
import type { StatusTone } from '../../components/StatusBadge'
import { translateKey } from '../../i18n'
import { formatNumberIn } from '../../i18n/format'
import { shiftUrl } from '../../utils/drillDown'
import { formatCurrency, formatDecimal, formatLitres, formatWithSign } from '../../utils/format'

export const BAND_BADGES: Record<RadarBand, { tone: StatusTone; icon: LucideIcon }> = {
  LOW: { tone: 'ok', icon: Check },
  WATCH: { tone: 'warn', icon: Eye },
  HIGH: { tone: 'bad', icon: AlertTriangle },
  NOT_ENOUGH_DATA: { tone: 'neutral', icon: HelpCircle },
}

const MONEY_PARAMS = new Set(['perShift', 'restPerShift', 'total'])
const METER_DECIMALS = 3

function readableNumber(name: string, value: number): string {
  const format = MONEY_PARAMS.has(name) ? formatCurrency : (size: number) => formatNumberIn(size)
  return value < 0 ? formatWithSign(value, format) : format(value)
}

function readableParams(params: Record<string, string | number>): Record<string, string> {
  return Object.fromEntries(Object.entries(params).map(([name, value]) => [
    name,
    typeof value === 'number' ? readableNumber(name, value) : String(value),
  ]))
}

/** The rule in words with the figures it used, e.g. "Short on 6 of 10 shifts (60%) …". */
export function reasonText(reason: ScoreReason): string {
  const key = reason.code === 'PERIOD_LOSS' && reason.params.percent == null ? 'PERIOD_LOSS_NO_SALES' : reason.code
  return translateKey(`radar:reasons.${key}`, readableParams(reason.params))
}

/** What happened, e.g. "Voided a credit entry of ₹750.00". */
export function evidenceText(evidence: RadarEvidence): string {
  const meter = (value: number | null) => (value == null ? '—' : formatDecimal(value, METER_DECIMALS))
  return translateKey(`radar:evidence.${evidence.kind}`, {
    amount: evidence.amount == null ? '—' : formatCurrency(evidence.amount),
    litres: evidence.litres == null ? '—' : formatLitres(Math.abs(evidence.litres)),
    usual: evidence.usualLitres == null ? '—' : formatLitres(evidence.usualLitres),
    percent: evidence.percent == null ? '—' : formatWithSign(evidence.percent, formatDecimal),
    from: meter(evidence.fromReading),
    to: meter(evidence.toReading),
  })
}

export interface EvidenceLink {
  to: string
  label: string
}

/** The page holding the record behind the event, or null when it has none. */
export function evidenceLink(evidence: RadarEvidence): EvidenceLink | null {
  const link = (to: string, labelKey: string) => ({ to, label: translateKey(`radar:evidence.${labelKey}`) })
  switch (evidence.kind) {
    case 'CREDIT_REASSIGNED':
      return evidence.referenceId == null ? null : link(`/dashboard/credit/${evidence.referenceId}`, 'openClient')
    case 'SETTLEMENT_VOID': return link('/dashboard/settlements', 'openSettlements')
    case 'EXPENSE_REJECTED': return link('/dashboard/expenses', 'openExpenses')
    case 'METER_AMENDMENT': return link('/dashboard/setup?section=nozzles', 'openNozzles')
    case 'LOSS_WEEK': return link('/dashboard/inventory?tab=reconciliation', 'openReconciliation')
    default: return evidence.shiftId == null ? null : link(shiftUrl(evidence.shiftId), 'openShift')
  }
}
