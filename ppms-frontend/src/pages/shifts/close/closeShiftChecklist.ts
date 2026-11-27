import type { TFunction } from 'i18next'
import { emptyNum } from '../components/creditRow'
import type { CreditRow } from '../components/creditRow'

export type StillNeededItem =
  | { kind: 'incomingOperator' }
  | { kind: 'endReadings'; count: number }
  | { kind: 'readingsBelowStart' }
  | { kind: 'testLitres' }
  | { kind: 'testLitresNote' }
  | { kind: 'payments' }
  | { kind: 'creditEntries'; count: number }
  | { kind: 'reason' }
  | { kind: 'pendingDrops'; count: number }

interface ChecklistInput {
  isHandover: boolean
  incomingChosen: boolean
  missingReadingCount: number
  readingsBelowStart: boolean
  paymentTouched: boolean
  creditRows: CreditRow[]
  hasDiscrepancy: boolean
  reasonFilled: boolean
  pendingDropCount: number
  testLitresInvalid?: boolean
  testLitresNoteMissing?: boolean
}

/** A credit entry the close request would refuse: no client from the list, no fuel or no amount. */
export function isCreditRowComplete(row: CreditRow): boolean {
  return row.clientName.trim() !== '' && row.clientId !== null && row.fuelType !== '' && emptyNum(row.amount) > 0
}

/** What is still missing before the close can be reviewed, in the order the form asks for it. */
export function stillNeededItems(input: ChecklistInput): StillNeededItem[] {
  const items: StillNeededItem[] = []
  if (input.isHandover && !input.incomingChosen) items.push({ kind: 'incomingOperator' })
  if (input.missingReadingCount > 0) items.push({ kind: 'endReadings', count: input.missingReadingCount })
  if (input.readingsBelowStart) items.push({ kind: 'readingsBelowStart' })
  if (input.testLitresInvalid) items.push({ kind: 'testLitres' })
  if (input.testLitresNoteMissing) items.push({ kind: 'testLitresNote' })
  const incompleteCreditRows = input.creditRows.filter((row) => !isCreditRowComplete(row)).length
  if (incompleteCreditRows > 0) items.push({ kind: 'creditEntries', count: incompleteCreditRows })
  if (input.pendingDropCount > 0) items.push({ kind: 'pendingDrops', count: input.pendingDropCount })
  if (!input.paymentTouched) items.push({ kind: 'payments' })
  if (input.hasDiscrepancy && !input.reasonFilled) items.push({ kind: 'reason' })
  return items
}

export function stillNeededLabel(item: StillNeededItem, t: TFunction<['shifts', 'common']>): string {
  switch (item.kind) {
    case 'incomingOperator': return t('close.stillNeeded.incomingOperator')
    case 'endReadings': return t('close.stillNeeded.endReadings', { count: item.count })
    case 'readingsBelowStart': return t('close.stillNeeded.readingsBelowStart')
    case 'testLitres': return t('testLitres.stillNeeded.invalid')
    case 'testLitresNote': return t('testLitres.stillNeeded.note')
    case 'payments': return t('close.stillNeeded.payments')
    case 'creditEntries': return t('close.stillNeeded.creditEntries', { count: item.count })
    case 'reason': return t('close.stillNeeded.reason')
    case 'pendingDrops': return t('close.stillNeeded.pendingDrops', { count: item.count })
  }
}
