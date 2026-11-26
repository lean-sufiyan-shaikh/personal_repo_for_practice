
// ── Record Delivery Modal ─────────────────────────────────────────────────────

import type { ReceiptCheckRequest } from '../../../api/inventoryApi'
import { emptyReceiptCheck } from './receiptCheck'
import type { ReceiptCheckInput, ReceiptResult } from './receiptCheck'

export interface DeliveryRow {
  id: number
  tankId: string
  qty: string
  costPrice: string
  check: ReceiptCheckInput
}

export type DeliveryRowField = 'tankId' | 'qty' | 'costPrice'

export interface PreparedDeliveryItem {
  tankId: number
  tankIdentifier: string
  fuelType: string
  quantityDelivered: number
  costPricePerUnit: number
  totalCost: number
  stockAfter: number | null
  capacity: number | null
  receiptCheck: ReceiptCheckRequest
  receipt: ReceiptResult
  densityDifference: number | null
  densityFlagged: boolean
}

export let _rowSeq = 0

export function newRow(defaultTankId = ''): DeliveryRow {
  return { id: ++_rowSeq, tankId: defaultTankId, qty: '', costPrice: '', check: emptyReceiptCheck() }
}
