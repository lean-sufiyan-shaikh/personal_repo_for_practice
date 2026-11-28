import { describe, expect, it } from 'vitest'
import type { CashEvent } from '../../api/cashApi'
import { balanceAfter, isOutflow, isReversible } from './cashFormat'

const handEntry: CashEvent = {
  id: 51, pumpId: 3, eventType: 'CASH_IN', amount: 400, description: 'Float from owner', eventDate: '2026-11-26',
  recordedByUserId: 1, createdAt: '2026-11-26T05:00:00Z', shiftId: null, recordedByHand: true, reversesEventId: null, reversedAt: null,
}

describe('balanceAfter', () => {
  it('adds a cash-in and takes off a cash-out', () => {
    expect(balanceAfter('CASH_IN', 2000, 750)).toBe(2750)
    expect(balanceAfter('CASH_OUT', 2000, 750)).toBe(1250)
  })

  it('has no balance after for the balance events, which move no money', () => {
    expect(balanceAfter('OPENING_BALANCE', 2000, 750)).toBeNull()
    expect(balanceAfter('CLOSING_BALANCE', 2000, 750)).toBeNull()
  })
})

describe('isReversible', () => {
  it('allows reversing a movement typed by hand once', () => {
    expect(isReversible(handEntry)).toBe(true)
    expect(isReversible({ ...handEntry, reversedAt: '2026-11-26T06:00:00Z' })).toBe(false)
    expect(isReversible({ ...handEntry, reversesEventId: 50 })).toBe(false)
    expect(isReversible({ ...handEntry, recordedByHand: false })).toBe(false)
    expect(isReversible({ ...handEntry, eventType: 'CLOSING_BALANCE' })).toBe(false)
  })
})

describe('isOutflow', () => {
  it('counts cash-outs and bank deposits as money leaving the safe', () => {
    expect(isOutflow('CASH_OUT')).toBe(true)
    expect(isOutflow('BANK_DEPOSIT')).toBe(true)
    expect(isOutflow('CASH_IN')).toBe(false)
    expect(isOutflow('CASH_DROP')).toBe(false)
  })
})
