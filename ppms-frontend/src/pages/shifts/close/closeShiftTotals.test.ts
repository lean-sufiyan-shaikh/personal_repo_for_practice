import { describe, expect, it } from 'vitest'
import type { FuelReading } from '../../../types/shift'
import { computeCloseShiftTotals, needsOwnerApproval } from './closeShiftTotals'

function reading(overrides: Partial<FuelReading> = {}): FuelReading {
  return {
    nozzleId: 1, fuelType: 'PETROL', startReading: 1000, endReading: null,
    priceSnapshot: 100, unitsSold: null, maxMeterValue: 999_999, ...overrides,
  }
}

const blankPayments = { cash: '', upi: '', card: '', fleetCard: '', preRecordedTotal: 0, newCreditTotal: 0, cashDropped: 0 }

function totals(input: Partial<Parameters<typeof computeCloseShiftTotals>[0]> = {}) {
  return computeCloseShiftTotals({
    fuelReadings: [reading()],
    endReadings: { 1: '1010' },
    noChangeNozzles: new Set(),
    ...blankPayments,
    ...input,
  })
}

describe('computeCloseShiftTotals', () => {
  it('prices litres before a price change at the old price and after it at the new one', () => {
    const changed = reading({ priceSegments: [
      { startReading: 1000, pricePerUnit: 100, startsAt: '2026-11-27T06:00:00+05:30', unitsSold: null },
      { startReading: 1010, pricePerUnit: 110, startsAt: '2026-11-27T10:00:00+05:30', unitsSold: null },
    ] })
    const result = totals({ fuelReadings: [changed], endReadings: { 1: '1030' }, cash: '3200' })

    expect(result.perFuelSales[0].parts).toEqual([{ units: 10, pricePerUnit: 100 }, { units: 20, pricePerUnit: 110 }])
    expect(result.perFuelSales[0].sold).toBe(30)
    expect(result.totalDue).toBe(3200)
    expect(result.isBalanced).toBe(true)
    expect(totals({ fuelReadings: [changed], endReadings: { 1: '1005' } }).readingsBelowStart).toBe(true)
  })

  it('closes a gap within the rounding allowance as balanced and keeps it as rounding', () => {
    const withinRounding = totals({ cash: '999.50', roundingTolerance: 1 })
    expect(withinRounding.isBalanced).toBe(true)
    expect(withinRounding.hasDiscrepancy).toBe(false)
    expect(withinRounding.roundingPaise).toBe(-50)

    const beyond = totals({ cash: '998.99', roundingTolerance: 1 })
    expect(beyond.hasDiscrepancy).toBe(true)
    expect(beyond.roundingPaise).toBe(0)
  })

  it('sends every difference to the owner when the approval limit is zero', () => {
    expect(needsOwnerApproval(-0.5, 0)).toBe(true)
    expect(needsOwnerApproval(-500, 500)).toBe(false)
    expect(needsOwnerApproval(-500.01, 500)).toBe(true)
  })

  it('counts confirmed drops as collected, so only the bag is compared with what is left', () => {
    const balanced = totals({ cash: '400', cashDropped: 600 })
    expect(balanced.totalCollected).toBe(1000)
    expect(balanced.isBalanced).toBe(true)

    const short = totals({ cash: '350', cashDropped: 600 })
    expect(short.diff).toBe(-50)
    expect(short.hasDiscrepancy).toBe(true)
  })

  it('is balanced when payments plus credit equal the meter value', () => {
    const result = totals({ cash: '600', upi: '200', card: '100', fleetCard: '50', newCreditTotal: 50 })
    expect(result.totalDue).toBe(1000)
    expect(result.totalCollected).toBe(1000)
    expect(result.isBalanced).toBe(true)
    expect(result.hasDiscrepancy).toBe(false)
    expect(result.canRoundOff).toBe(false)
  })

  it('reports a short shift with a negative difference', () => {
    const result = totals({ cash: '900' })
    expect(result.diff).toBe(-100)
    expect(result.isBalanced).toBe(false)
    expect(result.hasDiscrepancy).toBe(true)
    expect(result.canRoundOff).toBe(false)
  })

  it('reports an over shift with a positive difference', () => {
    const result = totals({ cash: '1250.50' })
    expect(result.diff).toBe(250.5)
    expect(result.hasDiscrepancy).toBe(true)
  })

  it('allows rounding off a gap under one rupee', () => {
    const result = totals({ cash: '999.01' })
    expect(result.diffPaise).toBe(-99)
    expect(result.hasDiscrepancy).toBe(true)
    expect(result.canRoundOff).toBe(true)
  })

  it('does not allow rounding off a gap of exactly one rupee', () => {
    expect(totals({ cash: '999' }).canRoundOff).toBe(false)
  })

  it('counts pre-recorded and new credit together as collected', () => {
    const result = totals({ cash: '400', preRecordedTotal: 350.25, newCreditTotal: 249.75 })
    expect(result.creditTotal).toBe(600)
    expect(result.isBalanced).toBe(true)
  })

  it('treats pre-recorded credit alone as a touched payment', () => {
    const result = totals({ preRecordedTotal: 1000 })
    expect(result.paymentTouched).toBe(true)
    expect(result.isBalanced).toBe(true)
  })

  it('does not compare while a reading is missing', () => {
    const result = totals({ endReadings: { 1: '' }, cash: '5' })
    expect(result.readingsComplete).toBe(false)
    expect(result.isBalanced).toBe(false)
    expect(result.hasDiscrepancy).toBe(false)
    expect(result.totalDue).toBe(0)
  })

  it('does not compare before any payment is entered', () => {
    const result = totals()
    expect(result.paymentTouched).toBe(false)
    expect(result.isBalanced).toBe(false)
    expect(result.hasDiscrepancy).toBe(false)
  })

  it('flags a closing reading below the opening one that is not a rollover', () => {
    const result = totals({ endReadings: { 1: '900' }, cash: '0' })
    expect(result.readingsBelowStart).toBe(true)
    expect(result.perFuelSales[0]).toMatchObject({ sold: 0, value: 0, isBelowStart: true })
    expect(result.hasDiscrepancy).toBe(false)
  })

  it('does not flag a no-change nozzle as below start', () => {
    const result = totals({ endReadings: { 1: '900' }, noChangeNozzles: new Set([1]) })
    expect(result.readingsBelowStart).toBe(false)
  })

  it('values a meter rollover as units past the maximum', () => {
    const result = totals({
      fuelReadings: [reading({ startReading: 99_995, maxMeterValue: 100_000 })],
      endReadings: { 1: '5' },
      cash: '1000',
    })
    expect(result.perFuelSales[0].sold).toBe(10)
    expect(result.totalDue).toBe(1000)
    expect(result.isBalanced).toBe(true)
  })

  it('rounds the total due once to paise across nozzles', () => {
    const result = totals({
      fuelReadings: [
        reading({ nozzleId: 1, priceSnapshot: 94.725 }),
        reading({ nozzleId: 2, priceSnapshot: 94.725 }),
      ],
      endReadings: { 1: '1001', 2: '1001' },
      cash: '189.45',
    })
    expect(result.totalDue).toBe(189.45)
    expect(result.isBalanced).toBe(true)
  })

  it('compares paise so floating-point sums still balance', () => {
    const result = totals({
      fuelReadings: [reading({ priceSnapshot: 0.3 })],
      endReadings: { 1: '1001' },
      cash: '0.1', upi: '0.2',
    })
    expect(result.diffPaise).toBe(0)
    expect(result.isBalanced).toBe(true)
  })
})
