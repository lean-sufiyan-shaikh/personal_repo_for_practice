import type { FuelReading } from '../../../types/shift'
import { roundToMillilitres, roundToPaise, toPaise } from '../../../utils/money'
import { amountOf, pricedParts } from '../../../utils/priceSegments'
import type { PricedPart } from '../../../utils/priceSegments'
import { formatDecimal } from '../../../utils/format'
import { isTestLitresValid, parseTestLitres, withoutTestLitres } from '../../../utils/testLitres'
import { emptyNum } from '../components/creditRow'

interface TotalsInput {
  fuelReadings: FuelReading[]
  endReadings: Record<number, string>
  /** Test litres poured back into the tank per nozzle; empty means none. */
  testLitres?: Record<number, string>
  noChangeNozzles: Set<number>
  cash: string
  upi: string
  card: string
  fleetCard: string
  preRecordedTotal: number
  newCreditTotal: number
  /** Confirmed drops already in the safe: collected on top of the cash in the bag. */
  cashDropped: number
  /** A difference up to this many rupees closes the shift balanced, as rounding. */
  roundingTolerance?: number
}

export interface FuelSale {
  reading: FuelReading
  /** Meter litres less test litres. */
  sold: number
  meterLitres: number
  testLitres: number
  testLitresInvalid: boolean
  value: number
  /** The litres at each price; more than one when the price changed during the shift. */
  parts: PricedPart[]
  isBelowStart: boolean
}

// Mirrors the backend: units rounded to 3 decimals and priced per price segment, the total due rounded once to paise.
export function computeCloseShiftTotals({
  fuelReadings, endReadings, testLitres = {}, noChangeNozzles, cash, upi, card, fleetCard, preRecordedTotal, newCreditTotal,
  cashDropped, roundingTolerance = 0,
}: TotalsInput) {
  const perFuelSales: FuelSale[] = fuelReadings.map((r) => {
    const endRaw = endReadings[r.nozzleId] ?? ''
    const meterParts = endRaw !== '' ? pricedParts(r, Number(endRaw)) : []
    const meterLitres = roundToMillilitres((meterParts ?? []).reduce((sum, part) => sum + part.units, 0))
    const test = parseTestLitres(testLitres[r.nozzleId])
    const testLitresInvalid = meterParts !== null && endRaw !== '' && !isTestLitresValid(test, meterLitres)
    const parts = testLitresInvalid ? meterParts ?? [] : withoutTestLitres(meterParts ?? [], test)
    return {
      reading: r,
      sold: roundToMillilitres(parts.reduce((sum, part) => sum + part.units, 0)),
      meterLitres,
      testLitres: Number.isFinite(test) ? test : 0,
      testLitresInvalid,
      value: amountOf(parts),
      parts,
      isBelowStart: meterParts === null && !noChangeNozzles.has(r.nozzleId),
    }
  })

  const totalDue       = roundToPaise(perFuelSales.reduce((s, x) => s + x.value, 0))
  // creditTotal for the close request = pre-recorded entries + new entries added here
  const creditTotal    = roundToPaise(preRecordedTotal + newCreditTotal)
  const totalCollected = roundToPaise(emptyNum(cash) + cashDropped + emptyNum(upi) + emptyNum(card) + emptyNum(fleetCard) + creditTotal)
  const diffPaise      = toPaise(totalCollected) - toPaise(totalDue)

  const readingsComplete = fuelReadings.every((r) => (endReadings[r.nozzleId] ?? '') !== '')
  const readingsBelowStart = perFuelSales.some((x) => x.isBelowStart)
  const testLitresInvalid = perFuelSales.some((x) => x.testLitresInvalid)
  // Consider "touched" if any payment field has a value, OR if pre-recorded credit exists
  const paymentTouched   = cash !== '' || upi !== '' || card !== '' || fleetCard !== '' || creditTotal > 0
  const readyToCompare   = readingsComplete && !readingsBelowStart && paymentTouched
  // The backend absorbs a gap up to the pump's rounding tolerance; anything larger needs a reason.
  const withinRounding   = Math.abs(diffPaise) <= toPaise(roundingTolerance)
  const hasDiscrepancy   = readyToCompare && !withinRounding

  return {
    perFuelSales,
    totalDue,
    creditTotal,
    totalCollected,
    diffPaise,
    diff: diffPaise / 100,
    readingsComplete,
    readingsBelowStart,
    testLitresInvalid,
    paymentTouched,
    isBalanced: readyToCompare && withinRounding,
    /** Collected minus due in paise that closes as rounding; zero when there is none. */
    roundingPaise: readyToCompare && withinRounding ? diffPaise : 0,
    hasDiscrepancy,
    canRoundOff: hasDiscrepancy && Math.abs(diffPaise) < 100,
  }
}

interface CashStillDueInput {
  totalDue: number
  cashDropped: number
  upi: string
  card: string
  fleetCard: string
  creditTotal: number
}

/** What the bag must hold for the shift to balance after drops, UPI, card, fleet card and credit; never below zero. */
export function cashStillDuePaise({ totalDue, cashDropped, upi, card, fleetCard, creditTotal }: CashStillDueInput): number {
  return Math.max(0,
    toPaise(totalDue) - toPaise(cashDropped) - toPaise(emptyNum(upi)) - toPaise(emptyNum(card)) - toPaise(emptyNum(fleetCard)) - toPaise(creditTotal))
}

/** The cash in paise that closes the gap exactly; null when the gap is larger than the cash entered. */
export function roundedOffCashPaise(cash: string, diffPaise: number): number | null {
  const newCashPaise = toPaise(emptyNum(cash)) - diffPaise
  return newCashPaise < 0 ? null : newCashPaise
}

export function discrepancyTypeOf(diff: number): 'SHORT' | 'OVER' | null {
  if (diff < 0) return 'SHORT'
  if (diff > 0) return 'OVER'
  return null
}

/** Whether a gap of this size goes to the owner for approval, mirroring the server's escalation rule. */
export function needsOwnerApproval(diff: number, escalationThreshold: number | null): boolean {
  return escalationThreshold !== null && Math.abs(diff) > escalationThreshold
}

/** "12.50 L" or "3.00 kg": the fuel's own unit, which the litre formatter would always print as L. */
export function formatUnits(amount: number, unit: string): string {
  return `${formatDecimal(amount)} ${unit}`
}
