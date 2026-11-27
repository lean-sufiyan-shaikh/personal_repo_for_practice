import { describe, expect, it } from 'vitest'
import type { FuelReading } from '../../../types/shift'
import { computeCloseShiftTotals } from './closeShiftTotals'
import { buildCloseShiftRequest } from './closeShiftRequest'
import { stillNeededItems } from './closeShiftChecklist'

function reading(overrides: Partial<FuelReading> = {}): FuelReading {
  return {
    nozzleId: 1, fuelType: 'PETROL', startReading: 1000, endReading: null,
    priceSnapshot: 100, unitsSold: null, maxMeterValue: 999_999, ...overrides,
  }
}

const blankPayments = { cash: '', upi: '', card: '', fleetCard: '', preRecordedTotal: 0, newCreditTotal: 0, cashDropped: 0 }

describe('test litres at close', () => {
  it('leaves test litres out of the litres sold and the amount due', () => {
    const result = computeCloseShiftTotals({
      fuelReadings: [reading()], endReadings: { 1: '1105' }, testLitres: { 1: '5' }, noChangeNozzles: new Set(),
      ...blankPayments, cash: '10000',
    })

    expect(result.perFuelSales[0].meterLitres).toBe(105)
    expect(result.perFuelSales[0].sold).toBe(100)
    expect(result.totalDue).toBe(10000)
    expect(result.isBalanced).toBe(true)
  })

  it('takes test litres off the latest price first when the price changed during the shift', () => {
    const changed = reading({ priceSegments: [
      { startReading: 1000, pricePerUnit: 100, startsAt: '2026-11-27T06:00:00+05:30', unitsSold: null },
      { startReading: 1010, pricePerUnit: 110, startsAt: '2026-11-27T10:00:00+05:30', unitsSold: null },
    ] })
    const result = computeCloseShiftTotals({
      fuelReadings: [changed], endReadings: { 1: '1015' }, testLitres: { 1: '7' }, noChangeNozzles: new Set(), ...blankPayments,
    })

    expect(result.perFuelSales[0].parts).toEqual([{ units: 8, pricePerUnit: 100 }, { units: 0, pricePerUnit: 110 }])
    expect(result.totalDue).toBe(800)
  })

  it('flags test litres above the meter litres and asks for them in the checklist', () => {
    const result = computeCloseShiftTotals({
      fuelReadings: [reading()], endReadings: { 1: '1004' }, testLitres: { 1: '5' }, noChangeNozzles: new Set(), ...blankPayments,
    })
    expect(result.testLitresInvalid).toBe(true)

    const items = stillNeededItems({
      isHandover: false, incomingChosen: true, missingReadingCount: 0, readingsBelowStart: false, paymentTouched: true,
      creditRows: [], hasDiscrepancy: false, reasonFilled: false, pendingDropCount: 0,
      testLitresInvalid: true, testLitresNoteMissing: true,
    })
    expect(items.map((item) => item.kind)).toEqual(['testLitres', 'testLitresNote'])
  })

  it('sends test litres per nozzle and the note only when there are some', () => {
    const base = {
      fuelReadings: [reading(), reading({ nozzleId: 2 })], endReadings: { 1: '1105', 2: '1010' }, readingsBelowStart: false,
      creditRows: [], cash: '0', upi: '', card: '', fleetCard: '', creditTotal: 0, discrepancyReason: '',
    }
    const result = buildCloseShiftRequest({ ...base, testLitres: { 1: '12', 2: '' }, testLitresNote: ' Calibration redone ' })

    expect('request' in result && result.request.fuelReadings).toEqual([
      { nozzleId: 1, endReading: 1105, testLitres: 12 },
      { nozzleId: 2, endReading: 1010 },
    ])
    expect('request' in result && result.request.testLitresNote).toBe('Calibration redone')
    const withoutTest = buildCloseShiftRequest(base)
    expect('request' in withoutTest && withoutTest.request.testLitresNote).toBeUndefined()
  })
})
