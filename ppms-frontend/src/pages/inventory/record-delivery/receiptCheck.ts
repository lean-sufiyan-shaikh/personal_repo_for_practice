import type { ReceiptCheckRequest, ReceiptNozzle } from '../../../api/inventoryApi'

export const DEFAULT_RECEIPT_TOLERANCE_PERCENT = 0.2
export const DEFAULT_DENSITY_TOLERANCE = 3

export interface ReceiptTolerances {
  receiptTolerancePercent: number
  densityTolerance: number
}

/** Typed text per field; a nozzle's "before" stays unset until edited and then reads its last known meter reading. */
export interface ReceiptCheckInput {
  dipBefore: string
  dipAfter: string
  readings: Record<number, { before?: string; after?: string }>
  observedDensity: string
  invoiceDensity: string
}

export type ReceiptField = 'dipBefore' | 'dipAfter' | 'readingsBefore' | 'readingsAfter'

export interface ReceiptResult {
  kind: 'MATCH' | 'SHORT' | 'OVER'
  receivedLitres: number
  soldBetweenLitres: number
  /** Received − invoiced: negative is short. */
  varianceLitres: number
  variancePercent: number
  beyondTolerance: boolean
}

export function emptyReceiptCheck(): ReceiptCheckInput {
  return { dipBefore: '', dipAfter: '', readings: {}, observedDensity: '', invoiceDensity: '' }
}

export function readingBefore(check: ReceiptCheckInput, nozzle: ReceiptNozzle): string {
  return check.readings[nozzle.nozzleId]?.before ?? String(nozzle.lastReading)
}

export function readingAfter(check: ReceiptCheckInput, nozzle: ReceiptNozzle): string {
  return check.readings[nozzle.nozzleId]?.after ?? ''
}

function parsed(text: string): number | null {
  if (text.trim() === '') return null
  const value = Number(text)
  return Number.isFinite(value) && value >= 0 ? value : null
}

/** Litres are kept to 3 decimals, so comparisons round there to avoid binary fractions tipping a boundary. */
function thousandths(value: number): number {
  return Math.round(value * 1000)
}

function unitsBetween(before: number, after: number, maxMeterValue: number): number {
  return after >= before ? after - before : maxMeterValue - before + after
}

export function missingReceiptFields(check: ReceiptCheckInput, nozzles: ReceiptNozzle[]): ReceiptField[] {
  const missing: ReceiptField[] = []
  if (parsed(check.dipBefore) === null) missing.push('dipBefore')
  if (nozzles.some(nozzle => parsed(readingBefore(check, nozzle)) === null)) missing.push('readingsBefore')
  if (parsed(check.dipAfter) === null) missing.push('dipAfter')
  if (nozzles.some(nozzle => parsed(readingAfter(check, nozzle)) === null)) missing.push('readingsAfter')
  return missing
}

export function soldBetweenLitres(check: ReceiptCheckInput, nozzles: ReceiptNozzle[]): number | null {
  let total = 0
  for (const nozzle of nozzles) {
    const before = parsed(readingBefore(check, nozzle))
    const after = parsed(readingAfter(check, nozzle))
    if (before === null || after === null) return null
    total += unitsBetween(before, after, nozzle.maxMeterValue)
  }
  return total
}

/** Received = dip after − dip before + litres sold from the tank between the two dips. */
export function receiptResult(
  check: ReceiptCheckInput, nozzles: ReceiptNozzle[], invoiceLitres: number, tolerancePercent: number,
): ReceiptResult | null {
  const before = parsed(check.dipBefore)
  const after = parsed(check.dipAfter)
  const sold = soldBetweenLitres(check, nozzles)
  if (before === null || after === null || sold === null || !(invoiceLitres > 0)) return null
  const received = after - before + sold
  const variance = received - invoiceLitres
  const allowed = thousandths(invoiceLitres * tolerancePercent / 100)
  const kind = thousandths(variance) === 0 ? 'MATCH' : variance < 0 ? 'SHORT' : 'OVER'
  return {
    kind,
    receivedLitres: received,
    soldBetweenLitres: sold,
    varianceLitres: variance,
    variancePercent: variance / invoiceLitres * 100,
    beyondTolerance: Math.abs(thousandths(variance)) > allowed,
  }
}

/** Observed − invoice density in kg/m³; null until both are entered. */
export function densityDifference(check: ReceiptCheckInput): number | null {
  const observed = parsed(check.observedDensity)
  const invoice = parsed(check.invoiceDensity)
  return observed === null || invoice === null ? null : observed - invoice
}

export function isDensityFlagged(difference: number | null, tolerance: number): boolean {
  return difference !== null && Math.round(Math.abs(difference) * 10) > Math.round(tolerance * 10)
}

export function toReceiptCheckRequest(check: ReceiptCheckInput, nozzles: ReceiptNozzle[]): ReceiptCheckRequest {
  return {
    dipBeforeLitres: Number(check.dipBefore),
    dipAfterLitres: Number(check.dipAfter),
    nozzleReadings: nozzles.map(nozzle => ({
      nozzleId: nozzle.nozzleId,
      readingBefore: Number(readingBefore(check, nozzle)),
      readingAfter: Number(readingAfter(check, nozzle)),
    })),
    observedDensity: parsed(check.observedDensity),
    invoiceDensity: parsed(check.invoiceDensity),
  }
}
