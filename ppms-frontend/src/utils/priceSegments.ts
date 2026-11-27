import type { FuelReading } from '../types/shift'
import { unitsSold } from './money'

/** Litres sold at one price. */
export interface PricedPart {
  units: number
  pricePerUnit: number
}

/**
 * A nozzle's litres from its opening to the closing reading, split at each price change the way the server prices
 * them. Null when the closing reading is below the last reading taken and the meter did not roll over.
 */
export function pricedParts(reading: FuelReading, endReading: number): PricedPart[] | null {
  const segments = reading.priceSegments ?? []
  if (segments.length === 0) {
    const units = unitsSold(reading.startReading, endReading, reading.maxMeterValue)
    return units === null ? null : [{ units, pricePerUnit: reading.priceSnapshot }]
  }
  const parts: PricedPart[] = []
  for (let index = 0; index < segments.length; index += 1) {
    const segmentEnd = index + 1 < segments.length ? segments[index + 1].startReading : endReading
    const units = unitsSold(segments[index].startReading, segmentEnd, reading.maxMeterValue)
    if (units === null) return null
    parts.push({ units, pricePerUnit: segments[index].pricePerUnit })
  }
  return parts
}

/** The price the nozzle sells at now: the latest price change's, else the price the shift opened with. */
export function currentPriceOf(reading: FuelReading): number {
  const segments = reading.priceSegments ?? []
  return segments.length > 0 ? segments[segments.length - 1].pricePerUnit : reading.priceSnapshot
}

export const amountOf = (parts: PricedPart[]) => parts.reduce((sum, part) => sum + part.units * part.pricePerUnit, 0)
