import type { OpenShiftNozzle } from '../../api/inventoryApi'

const ROLLOVER_START_FRACTION = 0.99

/** Litres dispensed since the shift opened, following the server's meter rollover rule; null while unknown or invalid. */
export function soldSinceOpening(nozzle: OpenShiftNozzle, reading: string): number | null {
  if (reading === '') return null
  const current = Number(reading)
  if (current >= nozzle.openingReading) return current - nozzle.openingReading
  if (nozzle.openingReading >= nozzle.maxMeterValue * ROLLOVER_START_FRACTION) {
    return nozzle.maxMeterValue - nozzle.openingReading + current
  }
  return null
}

export interface DipCheckFigures {
  /** Null until every open-shift nozzle has a valid reading. */
  soldOnOpenShifts: number | null
  expectedStock: number | null
  /** Measured less expected; null until both are known. */
  variance: number | null
}

export function dipCheckFigures(
  bookStock: number, nozzles: OpenShiftNozzle[], meterReadings: Record<number, string>, measured: string,
): DipCheckFigures {
  const soldPerNozzle = nozzles.map(n => soldSinceOpening(n, meterReadings[n.nozzleId] ?? ''))
  if (soldPerNozzle.some(sold => sold === null)) return { soldOnOpenShifts: null, expectedStock: null, variance: null }
  const soldOnOpenShifts = soldPerNozzle.reduce<number>((sum, sold) => sum + (sold ?? 0), 0)
  const expectedStock = bookStock - soldOnOpenShifts
  return { soldOnOpenShifts, expectedStock, variance: measured === '' ? null : Number(measured) - expectedStock }
}

export type VarianceKind = 'within' | 'surplus' | 'shortage'

/** A gap smaller than the tank's tolerance is within it; a gap equal to it already counts. */
export function varianceKind(variance: number, tolerance: number): VarianceKind {
  if (Math.abs(variance) < tolerance) return 'within'
  return variance > 0 ? 'surplus' : 'shortage'
}
