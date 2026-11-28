import { describe, expect, it } from 'vitest'
import type { NozzleDetail } from '../../../types/shift'
import { activeNozzlesOf, formatMeterReading } from './openShiftNozzles'

function nozzle(id: number, fuelType: NozzleDetail['fuelType'], lastReading = 1000, status: NozzleDetail['status'] = 'ACTIVE'): NozzleDetail {
  return { id, nozzleNumber: id, fuelType, lastReading, maxMeterValue: 999_999, tankId: 1, status }
}

describe('formatMeterReading', () => {
  it('groups the reading the Indian way with up to three decimals, in the fuel\'s own unit', () => {
    expect(formatMeterReading(nozzle(1, 'PETROL', 123456.789))).toEqual({ reading: '1,23,456.789', unit: 'L' })
    expect(formatMeterReading(nozzle(2, 'CNG', 50.5))).toEqual({ reading: '50.5', unit: 'kg' })
  })
})

describe('activeNozzlesOf', () => {
  it('leaves out nozzles that are not active', () => {
    const du = { id: 1, pumpId: 3, duNumber: 1, name: 'DU 1', status: 'ACTIVE', nozzles: [nozzle(1, 'PETROL'), nozzle(2, 'DIESEL', 0, 'INACTIVE')] }
    expect(activeNozzlesOf(du).map((n) => n.id)).toEqual([1])
  })
})
