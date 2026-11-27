import client from './client'
import type { PagedResponse } from '../types/paged'

export interface Supplier {
  id: number
  pumpId: number
  name: string
  contactName: string | null
  phone: string | null
  email: string | null
  notes: string | null
  active: boolean
  /** What the pump owed the supplier on openingBalanceDate; negative is an advance. */
  openingBalance: number
  openingBalanceDate: string | null
  /** The supplier a tanker delivery takes when none is picked. */
  defaultSupplier: boolean
  createdAt: string
}

export interface SaveSupplierRequest {
  name: string
  contactName?: string
  phone?: string
  email?: string
  notes?: string
  /** Left out to keep the current balance; a non-zero balance needs openingBalanceDate. */
  openingBalance?: number
  openingBalanceDate?: string
}

export interface SupplierAging {
  days0To15: number
  days16To30: number
  days31To60: number
  over60Days: number
}

export interface SupplierBalance {
  supplierId: number
  supplierName: string
  active: boolean
  defaultSupplier: boolean
  openingBalance: number
  openingBalanceDate: string | null
  totalDebit: number
  totalCredit: number
  outstanding: number
  advance: number
  aging: SupplierAging
  lastDeliveryDate: string | null
  lastPaymentDate: string | null
}

export interface SupplierBalances {
  suppliers: SupplierBalance[]
  /** Tanker invoices booked to no supplier, so in no supplier's balance. */
  unassigned: { invoiceCount: number; amount: number }
  totalOutstanding: number
  totalAdvance: number
}

export type LedgerEntryType = 'OPENING_BALANCE' | 'DELIVERY' | 'CREDIT_NOTE' | 'PAYMENT'

export interface LedgerEntry {
  date: string | null
  type: LedgerEntryType
  reference: string | null
  description: string | null
  debit: number
  credit: number
  /** What the pump owes after this entry; negative is an advance. */
  balance: number
  paymentId: number | null
  paymentMode: string | null
  deliveryIds: number[]
}

export interface SupplierLedger {
  supplierId: number
  supplierName: string
  active: boolean
  defaultSupplier: boolean
  openingBalance: number
  openingBalanceDate: string | null
  entries: LedgerEntry[]
  totalDebit: number
  totalCredit: number
  balance: number
  outstanding: number
  advance: number
  aging: SupplierAging
  asOf: string
}

export type SupplierPaymentMode = 'CASH' | 'UPI' | 'BANK_TRANSFER' | 'CHEQUE' | 'OTHER'

export const SUPPLIER_PAYMENT_MODES: SupplierPaymentMode[] = ['BANK_TRANSFER', 'CHEQUE', 'UPI', 'CASH', 'OTHER']

export interface SupplierPayment {
  id: number
  pumpId: number
  supplierId: number
  supplierName: string
  deliveryId: number | null
  amount: number
  paymentDate: string
  paymentMode: SupplierPaymentMode
  referenceNo: string | null
  notes: string | null
  recordedByUserId: number
  recordedByName: string
  createdAt: string
  voidStatus: 'ACTIVE' | 'VOIDED'
  voidReason: string | null
  voidedAt: string | null
  cashEventId: number | null
}

export interface RecordSupplierPaymentRequest {
  supplierId: number
  amount: number
  paymentDate: string
  paymentMode: SupplierPaymentMode
  referenceNo?: string
  notes?: string
}

export const supplierApi = {
  /** Active suppliers, by name. */
  list: (pumpId: number): Promise<Supplier[]> =>
    client.get(`/pumps/${pumpId}/suppliers`).then(r => r.data),

  create: (pumpId: number, request: SaveSupplierRequest): Promise<Supplier> =>
    client.post(`/pumps/${pumpId}/suppliers`, request).then(r => r.data),

  update: (pumpId: number, supplierId: number, request: SaveSupplierRequest): Promise<Supplier> =>
    client.put(`/pumps/${pumpId}/suppliers/${supplierId}`, request).then(r => r.data),

  makeDefault: (pumpId: number, supplierId: number): Promise<Supplier> =>
    client.post(`/pumps/${pumpId}/suppliers/${supplierId}/default`).then(r => r.data),

  getBalances: (pumpId: number): Promise<SupplierBalances> =>
    client.get(`/pumps/${pumpId}/supplier-ledger`).then(r => r.data),

  getLedger: (pumpId: number, supplierId: number): Promise<SupplierLedger> =>
    client.get(`/pumps/${pumpId}/supplier-ledger/${supplierId}`).then(r => r.data),

  /** Payments to one supplier, voided ones included, newest first. */
  listPayments: (pumpId: number, supplierId: number, page = 0, size = 50): Promise<PagedResponse<SupplierPayment>> =>
    client.get(`/pumps/${pumpId}/supplier-payments`, { params: { supplierId, page, size } }).then(r => r.data),

  /** A resend with the same Idempotency-Key returns the first payment instead of recording two. */
  recordPayment: (pumpId: number, request: RecordSupplierPaymentRequest, idempotencyKey: string): Promise<SupplierPayment> =>
    client.post(`/pumps/${pumpId}/supplier-payments`, request, { headers: { 'Idempotency-Key': idempotencyKey } })
      .then(r => r.data),

  voidPayment: (pumpId: number, paymentId: number, reason: string): Promise<SupplierPayment> =>
    client.post(`/pumps/${pumpId}/supplier-payments/${paymentId}/void`, { reason }).then(r => r.data),
}
