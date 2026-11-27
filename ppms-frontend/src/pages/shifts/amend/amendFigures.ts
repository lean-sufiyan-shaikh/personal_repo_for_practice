import type { FuelReading, Shift } from '../../../types/shift'

export interface AmendDraft {
  endReadings: Record<number, string>
  cashCollected: string
  upiCollected: string
  cardCollected: string
  fleetCardCollected: string
}

export interface ShiftFigures {
  litresSold: number
  amountDue: number
  totalCollected: number
  /** Collected minus due: below zero is short, above zero is over. */
  difference: number
}

const OPEN_STATUSES = new Set(['OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE'])
const SETTLED_WITH_MONEY = new Set(['CASH_RECOVERY', 'SALARY_DEDUCTION'])

/** Owners and admins correct a closed shift, unless its discrepancy was already settled with money. */
export function canAmendShift(shift: Shift, role: string | undefined): boolean {
  return (role === 'OWNER' || role === 'ADMIN')
    && !OPEN_STATUSES.has(shift.status)
    && !SETTLED_WITH_MONEY.has(shift.discrepancyResolution ?? '')
}

export function bagCashOf(shift: Shift): number {
  return (shift.cashCollected ?? 0) - (shift.cashDropped ?? 0)
}

function roundMoney(amount: number): number {
  return Math.round(amount * 100) / 100
}

function amountOf(value: string): number {
  const amount = Number(value)
  return Number.isFinite(amount) ? amount : 0
}

/** Litres between two readings of a meter that wraps to zero at its maximum, as the server works it out. */
export function litresBetween(reading: FuelReading, endReading: number): number {
  if (endReading >= reading.startReading || reading.maxMeterValue == null) return Math.max(0, endReading - reading.startReading)
  return reading.maxMeterValue - reading.startReading + endReading
}

export function draftOf(shift: Shift): AmendDraft {
  return {
    endReadings: Object.fromEntries(shift.fuelReadings.map((reading) => [reading.nozzleId, String(reading.endReading ?? '')])),
    cashCollected: String(roundMoney(bagCashOf(shift))),
    upiCollected: String(shift.upiCollected ?? 0),
    cardCollected: String(shift.cardCollected ?? 0),
    fleetCardCollected: String(shift.fleetCardCollected ?? 0),
  }
}

export function currentFigures(shift: Shift): ShiftFigures {
  const litresSold = shift.fuelReadings.reduce((sum, reading) => sum + (reading.unitsSold ?? 0), 0)
  const amountDue = shift.totalAmountDue ?? 0
  const totalCollected = roundMoney((shift.cashCollected ?? 0) + (shift.upiCollected ?? 0) + (shift.cardCollected ?? 0)
    + (shift.fleetCardCollected ?? 0) + (shift.creditTotal ?? 0))
  return { litresSold, amountDue, totalCollected, difference: roundMoney(totalCollected - amountDue) }
}

export function amendedFigures(shift: Shift, draft: AmendDraft): ShiftFigures {
  let litresSold = 0
  let due = 0
  for (const reading of shift.fuelReadings) {
    const litres = litresBetween(reading, amountOf(draft.endReadings[reading.nozzleId] ?? ''))
    litresSold += litres
    due += litres * reading.priceSnapshot
  }
  const amountDue = roundMoney(due)
  const totalCollected = roundMoney(amountOf(draft.cashCollected) + (shift.cashDropped ?? 0) + amountOf(draft.upiCollected)
    + amountOf(draft.cardCollected) + amountOf(draft.fleetCardCollected) + (shift.creditTotal ?? 0))
  return { litresSold, amountDue, totalCollected, difference: roundMoney(totalCollected - amountDue) }
}

/** The names of fields still empty or below zero; the form cannot be sent until there are none. */
export function invalidFields(shift: Shift, draft: AmendDraft): string[] {
  const values: [string, string][] = [
    ...shift.fuelReadings.map((reading): [string, string] => [`reading-${reading.nozzleId}`, draft.endReadings[reading.nozzleId] ?? '']),
    ['cashCollected', draft.cashCollected],
    ['upiCollected', draft.upiCollected],
    ['cardCollected', draft.cardCollected],
    ['fleetCardCollected', draft.fleetCardCollected],
  ]
  return values.filter(([, value]) => value.trim() === '' || !(Number(value) >= 0)).map(([name]) => name)
}
