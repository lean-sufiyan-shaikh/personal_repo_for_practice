import { roundToMillilitres, roundToPaise } from './money'
import type { PricedPart } from './priceSegments'

/** Empty means none; anything that is not a number of litres is NaN. */
export function parseTestLitres(text: string | undefined): number {
  if (text === undefined || text.trim() === '') return 0
  const litres = Number(text)
  return Number.isFinite(litres) ? roundToPaise(litres) : Number.NaN
}

/** Test litres must be a number from zero up to the litres the meter moved. */
export function isTestLitresValid(testLitres: number, meterLitres: number): boolean {
  return Number.isFinite(testLitres) && testLitres >= 0 && testLitres <= roundToMillilitres(meterLitres) + 1e-9
}

/**
 * The priced parts with the test litres taken off the latest prices first, as the server does: test litres are known
 * only at close.
 */
export function withoutTestLitres(parts: PricedPart[], testLitres: number): PricedPart[] {
  let left = Number.isFinite(testLitres) && testLitres > 0 ? testLitres : 0
  const reduced = [...parts]
  for (let index = reduced.length - 1; index >= 0 && left > 0; index -= 1) {
    const taken = Math.min(reduced[index].units, left)
    reduced[index] = { ...reduced[index], units: roundToMillilitres(reduced[index].units - taken) }
    left = roundToMillilitres(left - taken)
  }
  return reduced
}

/** Whether any nozzle's test litres go over the pump's limit, which needs a note. */
export function anyAboveTestLimit(testLitres: number[], maxTestLitres: number | undefined): boolean {
  return maxTestLitres !== undefined && testLitres.some((litres) => Number.isFinite(litres) && litres > maxTestLitres)
}
