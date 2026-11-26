import { describe, expect, it } from 'vitest'
import type { ReceiptNozzle } from '../../../api/inventoryApi'
import {
  densityDifference, emptyReceiptCheck, isDensityFlagged, missingReceiptFields, receiptResult, toReceiptCheckRequest,
} from './receiptCheck'
import type { ReceiptCheckInput } from './receiptCheck'

const nozzle: ReceiptNozzle = {
  nozzleId: 5, nozzleNumber: 2, duName: null, tankId: 1, fuelType: 'DIESEL', lastReading: 500, maxMeterValue: 999_999.999,
}

function check(overrides: Partial<ReceiptCheckInput>): ReceiptCheckInput {
  return { ...emptyReceiptCheck(), ...overrides }
}

describe('receiptResult', () => {
  it('adds the litres sold between the dips to what the dips show', () => {
    const result = receiptResult(check({ dipBefore: '2000', dipAfter: '13966', readings: { 5: { after: '510' } } }), [nozzle], 12_000, 0.2)
    expect(result).toMatchObject({ kind: 'SHORT', soldBetweenLitres: 10, beyondTolerance: false })
    expect(result?.receivedLitres).toBeCloseTo(11_976)
  })

  it('treats a shortage exactly at the tolerance as within it and anything above as beyond it', () => {
    const atLimit = receiptResult(check({ dipBefore: '0', dipAfter: '11976' }), [], 12_000, 0.2)
    const aboveLimit = receiptResult(check({ dipBefore: '0', dipAfter: '11975.999' }), [], 12_000, 0.2)
    expect(atLimit?.beyondTolerance).toBe(false)
    expect(aboveLimit?.beyondTolerance).toBe(true)
  })

  it('reports an over-delivery and a match', () => {
    expect(receiptResult(check({ dipBefore: '0', dipAfter: '12100' }), [], 12_000, 0.2)).toMatchObject({ kind: 'OVER', beyondTolerance: true })
    expect(receiptResult(check({ dipBefore: '0', dipAfter: '12000' }), [], 12_000, 0.2)).toMatchObject({ kind: 'MATCH', beyondTolerance: false })
  })

  it('counts a meter that rolled over between the readings', () => {
    const rolled = { ...nozzle, lastReading: 999_995 }
    const result = receiptResult(check({ dipBefore: '0', dipAfter: '1000', readings: { 5: { after: '5' } } }), [rolled], 1_000, 0.2)
    expect(result?.soldBetweenLitres).toBeCloseTo(9.999)
  })

  it('waits until both dips and every reading are entered', () => {
    expect(receiptResult(check({ dipBefore: '0', dipAfter: '1000' }), [nozzle], 1_000, 0.2)).toBeNull()
    expect(receiptResult(check({ dipBefore: '0' }), [], 1_000, 0.2)).toBeNull()
  })
})

describe('missingReceiptFields', () => {
  it('names what is still blank, with the before reading prefilled from the last known reading', () => {
    expect(missingReceiptFields(emptyReceiptCheck(), [nozzle])).toEqual(['dipBefore', 'dipAfter', 'readingsAfter'])
    expect(missingReceiptFields(check({ readings: { 5: { before: '' } } }), [nozzle]))
      .toEqual(['dipBefore', 'readingsBefore', 'dipAfter', 'readingsAfter'])
  })
})

describe('density', () => {
  it('flags only a difference above the tolerance', () => {
    expect(densityDifference(check({ observedDensity: '830.5', invoiceDensity: '826' }))).toBeCloseTo(4.5)
    expect(isDensityFlagged(4.5, 3)).toBe(true)
    expect(isDensityFlagged(-3, 3)).toBe(false)
    expect(isDensityFlagged(null, 3)).toBe(false)
    expect(densityDifference(check({ observedDensity: '830' }))).toBeNull()
  })
})

describe('toReceiptCheckRequest', () => {
  it('sends the prefilled before reading and leaves blank densities out', () => {
    expect(toReceiptCheckRequest(check({ dipBefore: '10', dipAfter: '20', readings: { 5: { after: '501' } } }), [nozzle])).toEqual({
      dipBeforeLitres: 10, dipAfterLitres: 20,
      nozzleReadings: [{ nozzleId: 5, readingBefore: 500, readingAfter: 501 }],
      observedDensity: null, invoiceDensity: null,
    })
  })
})
