import type { OperatorCollection } from '../../../types/shift'

export type CollectionField = 'cashCollected' | 'upiCollected' | 'cardCollected' | 'fleetCardCollected' | 'creditTotal'

export const COLLECTION_FIELDS: CollectionField[] = ['cashCollected', 'upiCollected', 'cardCollected', 'fleetCardCollected', 'creditTotal']

/** What was typed per operator, by operator id; each figure is text while it is being edited. */
export type OperatorCollectionRows = Record<number, Partial<Record<CollectionField, string>>>

const amountOf = (text: string | undefined): number => {
  const amount = Number(text)
  return text && Number.isFinite(amount) && amount > 0 ? amount : 0
}

/** The split to send with the close: empty when nothing was typed, so the close keeps the combined figures only. */
export function operatorCollectionsOf(rows: OperatorCollectionRows): OperatorCollection[] {
  const typed = Object.values(rows).some((row) => COLLECTION_FIELDS.some((field) => (row[field] ?? '') !== ''))
  if (!typed) return []
  return Object.entries(rows).map(([operatorId, row]) => ({
    operatorId: Number(operatorId),
    cashCollected: amountOf(row.cashCollected),
    upiCollected: amountOf(row.upiCollected),
    cardCollected: amountOf(row.cardCollected),
    fleetCardCollected: amountOf(row.fleetCardCollected),
    creditTotal: amountOf(row.creditTotal),
  }))
}

/** The total of one figure across the operators, to compare with the shift's own. */
export function splitTotalOf(rows: OperatorCollectionRows, field: CollectionField): number {
  return Object.values(rows).reduce((sum, row) => sum + amountOf(row[field]), 0)
}
