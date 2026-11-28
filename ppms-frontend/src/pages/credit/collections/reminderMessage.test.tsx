import { describe, expect, it } from 'vitest'
import { callLink, portalLink, reminderMessage } from './reminderMessage'

describe('reminderMessage', () => {
  it('says what is owed and for how long, links the balance page and adds the UPI link when there is one', () => {
    const message = reminderMessage({ clientName: 'Acme', outstanding: 1500.5, daysOverdue: 9, upiPayLink: 'upi://pay?pa=a@b' }, 3)

    expect(message.split('\n')).toEqual([
      'Namaste Acme, ₹1,500.50 is outstanding on your credit account, 9 days overdue. Please pay at the earliest.',
      `See your balance: ${portalLink(3)}`,
      'Pay by UPI: upi://pay?pa=a@b',
    ])
  })

  it('leaves the UPI line out when the pump has no UPI details', () => {
    expect(reminderMessage({ clientName: 'Acme', outstanding: 10, daysOverdue: 7, upiPayLink: null }, 3)).not.toContain('UPI')
  })

  it('calls the digits of the phone, or nothing without one', () => {
    expect(callLink('+91 98765-43210')).toBe('tel:+919876543210')
    expect(callLink(null)).toBeNull()
    expect(callLink('12')).toBeNull()
  })
})
