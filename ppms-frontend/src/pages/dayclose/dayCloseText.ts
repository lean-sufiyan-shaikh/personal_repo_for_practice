import { AlertTriangle, Check, Hourglass, RotateCcw, XCircle } from 'lucide-react'
import type { ChecklistItem, DayCloseStatus, FlagColour, FlagReason } from '../../api/dayCloseApi'
import type { StatusBadgeSpec } from '../../components/statusBadges'
import type { StatusTone } from '../../components/StatusBadge'
import { codeLabel } from '../../constants/labels'
import { translateKey } from '../../i18n'
import { formatCurrency, formatLitres } from '../../utils/format'
import { formatIstDate } from '../../utils/date'

const MONEY_PARAMS = new Set(['amount', 'toleranceAmount'])

/** Server params ready for a translation: money and litres formatted, codes as their labels, dates readable. */
export function displayParams(params: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(params).map(([name, value]) => {
    if (MONEY_PARAMS.has(name)) return [name, formatCurrency(Number(value))]
    if (name === 'litres') return [name, formatLitres(Number(value))]
    if (name === 'date' && typeof value === 'string') return [name, formatIstDate(value)]
    if (name === 'fuelTypeCodes' && Array.isArray(value)) return ['fuelTypes', value.map((code) => codeLabel(String(code))).join(', ')]
    if (name.endsWith('Code') && typeof value === 'string') return [name, codeLabel(value)]
    return [name, value]
  }))
}

export function reasonText(reason: FlagReason): string {
  return translateKey(`dayclose:reasons.${reason.code}`, { ...displayParams(reason.params), defaultValue: reason.text })
}

export function checkText(item: ChecklistItem): string {
  const key = item.blocking
    ? `dayclose:checks.${item.code}.${item.done ? 'done' : 'missing'}`
    : `dayclose:checks.${item.code}`
  return translateKey(key, { ...displayParams(item.params), defaultValue: item.text })
}

export function fixLabel(item: ChecklistItem): string {
  return translateKey(`dayclose:fix.${item.code}`)
}

const FLAG_LOOK: Record<FlagColour, Omit<StatusBadgeSpec, 'label'>> = {
  GREEN: { tone: 'ok', icon: Check },
  AMBER: { tone: 'warn', icon: AlertTriangle },
  RED: { tone: 'bad', icon: XCircle },
}

export function flagBadge(colour: FlagColour): StatusBadgeSpec {
  return { ...FLAG_LOOK[colour], label: translateKey(`dayclose:flags.${colour}`) }
}

export function flagTone(colour: FlagColour): StatusTone {
  return FLAG_LOOK[colour].tone
}

const STATUS_LOOK: Record<DayCloseStatus, Omit<StatusBadgeSpec, 'label'>> = {
  CLOSED: { tone: 'warn', icon: Hourglass },
  APPROVED: { tone: 'ok', icon: Check },
  REOPENED: { tone: 'info', icon: RotateCcw },
}

export function dayStatusBadge(status: DayCloseStatus): StatusBadgeSpec {
  return { ...STATUS_LOOK[status], label: translateKey(`dayclose:status.${status}`) }
}

/** A closed or approved day refuses changes until an owner or admin re-opens it. */
export function isLocked(status: DayCloseStatus | null | undefined): boolean {
  return status === 'CLOSED' || status === 'APPROVED'
}
