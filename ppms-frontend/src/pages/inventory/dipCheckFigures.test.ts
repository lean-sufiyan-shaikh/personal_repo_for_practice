import { describe, expect, it } from 'vitest'
import type { OpenShiftNozzle } from '../../api/inventoryApi'
import { dipCheckFigures, soldSinceOpening, varianceKind } from './dipCheckFigures'

const openNozzle: OpenShiftNozzle = {
  shiftId: 9, nozzleId: 21, nozzleNumber: 1, duName: 'DU-1', fuelType: 'PETROL', openingReading: 1000, maxMeterValue: 99_999,
}

describe('soldSinceOpening', () => {
  it('is the reading less the opening reading', () => {
    expect(soldSinceOpening(openNozzle, '1030')).toBe(30)
  })

  it('follows a meter that rolled over from near its maximum', () => {
    expect(soldSinceOpening({ ...openNozzle, openingReading: 99_990 }, '20')).toBe(29)
  })

  it('is unknown while blank or below an opening reading that cannot roll over', () => {
    expect(soldSinceOpening(openNozzle, '')).toBeNull()
    expect(soldSinceOpening(openNozzle, '990')).toBeNull()
  })
})

describe('dipCheckFigures', () => {
  it('takes the open-shift sales off the book stock and compares the dip with what is left', () => {
    expect(dipCheckFigures(1000, [openNozzle], { 21: '1030' }, '965')).toEqual({ soldOnOpenShifts: 30, expectedStock: 970, variance: -5 })
  })

  it('compares the dip with the book stock when no shift is open', () => {
    expect(dipCheckFigures(1000, [], {}, '900')).toEqual({ soldOnOpenShifts: 0, expectedStock: 1000, variance: -100 })
  })

  it('waits for every meter reading and the dip before working anything out', () => {
    expect(dipCheckFigures(1000, [openNozzle], {}, '965')).toEqual({ soldOnOpenShifts: null, expectedStock: null, variance: null })
    expect(dipCheckFigures(1000, [openNozzle], { 21: '1030' }, '')).toEqual({ soldOnOpenShifts: 30, expectedStock: 970, variance: null })
  })
})

describe('varianceKind', () => {
  it('is within a tolerance it stays under, and a surplus or shortage once it reaches it', () => {
    expect(varianceKind(-5, 50)).toBe('within')
    expect(varianceKind(-50, 50)).toBe('shortage')
    expect(varianceKind(50, 50)).toBe('surplus')
  })
})
