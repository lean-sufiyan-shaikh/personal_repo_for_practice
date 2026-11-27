import { translateKey } from '../../../i18n'
import { formatCurrency } from '../../../utils/format'

interface ReminderFacts {
  clientName: string
  outstanding: number
  daysOverdue: number
  upiPayLink: string | null
}

/** The client's balance page, with the pump filled in so they only type their phone and PIN. */
export function portalLink(pumpId: number): string {
  return `${window.location.origin}/portal/credit-balance?pump=${pumpId}`
}

/** The reminder sent to a client, in the sender's language: what is owed, for how long, and where to see and pay it. */
export function reminderMessage(facts: ReminderFacts, pumpId: number): string {
  const lines = [
    translateKey('collections:remind.message', {
      clientName: facts.clientName,
      amount: formatCurrency(facts.outstanding),
      days: facts.daysOverdue,
    }),
    translateKey('collections:remind.messagePortal', { link: portalLink(pumpId) }),
  ]
  if (facts.upiPayLink) lines.push(translateKey('collections:remind.messageUpi', { link: facts.upiPayLink }))
  return lines.join('\n')
}

/** A tel: link for the phone, or null when there is no number to call. */
export function callLink(phone: string | null): string | null {
  const digits = (phone ?? '').replace(/[^\d+]/g, '')
  return digits.length >= 6 ? `tel:${digits}` : null
}
