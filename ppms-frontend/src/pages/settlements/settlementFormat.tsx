import { Smartphone, CreditCard, Truck } from 'lucide-react'
import type { ReactNode } from 'react'
import type { SettlementPaymentType } from '../../api/paymentSettlementApi'
import { translateKey } from '../../i18n'

// ── Constants ──────────────────────────────────────────────────────────────

export const TYPES: SettlementPaymentType[] = ['UPI', 'CARD', 'FLEET_CARD']

export function typeLabel(type: SettlementPaymentType): string {
  return translateKey(`settlements:types.${type}`)
}

export const TYPE_ICONS: Record<SettlementPaymentType, ReactNode> = {
  UPI:        <Smartphone size={16} strokeWidth={2} />,
  CARD:       <CreditCard size={16} strokeWidth={2} />,
  FLEET_CARD: <Truck size={16} strokeWidth={2} />,
}

export const TYPE_ACCENT: Record<SettlementPaymentType, { icon: string; badge: string; pill: string; pillActive: string }> = {
  UPI:        { icon: 'bg-info',    badge: 'bg-info-bg text-info',            pill: 'border-info text-info hover:bg-info-bg',                 pillActive: 'bg-info text-on-primary border-info'       },
  CARD:       { icon: 'bg-primary', badge: 'bg-surface-sunken text-primary',  pill: 'border-primary text-primary hover:bg-surface-sunken',    pillActive: 'bg-primary text-on-primary border-primary' },
  FLEET_CARD: { icon: 'bg-neutral', badge: 'bg-neutral-bg text-neutral',      pill: 'border-control text-neutral hover:bg-neutral-bg',        pillActive: 'bg-neutral text-on-primary border-neutral' },
}

// ── Helpers ────────────────────────────────────────────────────────────────

export { formatCurrency as fmtAmt } from '../../utils/format'

export { formatIstDate as fmtDate } from '../../utils/date'

export function getPending(wallet: { upiPending: number; cardPending: number; fleetCardPending: number } | undefined, type: SettlementPaymentType): number {
  if (!wallet) return 0
  return type === 'UPI' ? wallet.upiPending : type === 'CARD' ? wallet.cardPending : wallet.fleetCardPending
}
