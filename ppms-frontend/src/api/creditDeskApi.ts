import client from './client'
import type { CreditClient, CreditPaymentMode } from './creditApi'

/** A debit a credit note or a dispute can name; the values match the ledger row types. */
export type CreditDebitType = 'SALE' | 'COUNTER_SALE' | 'INTEREST' | 'BOUNCE_CHARGE'

export const DEBIT_TYPES: readonly string[] = ['SALE', 'COUNTER_SALE', 'INTEREST', 'BOUNCE_CHARGE']

export interface CreditAdjustment {
  id: number
  clientId: number
  kind: 'CREDIT_NOTE' | 'WRITE_OFF' | 'DEPOSIT_APPLIED' | 'ADVANCE_REFUND'
  amount: number
  adjustmentDate: string
  reason: string
  debitType: CreditDebitType | null
  debitId: number | null
  /** Set when the credit note also produced a GST credit note, because its sale was on an issued invoice. */
  gstCreditNoteId: number | null
  gstCreditNoteNumber: string | null
}

export interface IssueCreditNoteRequest {
  amount: number
  reason: string
  debitType?: CreditDebitType
  debitId?: number
  returnedQuantity?: number
}

/** Money for an advance refund, or a security deposit received, applied or refunded. */
export interface MoneyMovementRequest {
  amount: number
  paymentMode?: CreditPaymentMode
  referenceNo?: string
  reason?: string
  date?: string
}

export interface GstCreditNote {
  id: number
  clientId: number
  creditNoteNumber: string
  issuedOn: string
  invoiceId: number
  invoiceNumber: string
  reason: string
  description: string
  quantity: number | null
  taxableValue: number | null
  cgst: number
  sgst: number
  igst: number
  total: number
}

const withKey = (idempotencyKey?: string) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

const ledger = (pumpId: number) => `/pumps/${pumpId}/credit-ledger`

export const creditDeskApi = {
  issueCreditNote: (pumpId: number, clientId: number, req: IssueCreditNoteRequest, idempotencyKey?: string) =>
    client.post<CreditAdjustment>(`${ledger(pumpId)}/${clientId}/credit-notes`, req, withKey(idempotencyKey)).then(r => r.data),

  refundAdvance: (pumpId: number, clientId: number, req: MoneyMovementRequest, idempotencyKey?: string) =>
    client.post<CreditAdjustment>(`${ledger(pumpId)}/${clientId}/advance-refunds`, req, withKey(idempotencyKey)).then(r => r.data),

  receiveDeposit: (pumpId: number, clientId: number, req: MoneyMovementRequest, idempotencyKey?: string) =>
    client.post<unknown>(`${ledger(pumpId)}/${clientId}/deposits`, req, withKey(idempotencyKey)).then(r => r.data),

  applyDeposit: (pumpId: number, clientId: number, req: MoneyMovementRequest, idempotencyKey?: string) =>
    client.post<unknown>(`${ledger(pumpId)}/${clientId}/deposits/apply`, req, withKey(idempotencyKey)).then(r => r.data),

  refundDeposit: (pumpId: number, clientId: number, req: MoneyMovementRequest, idempotencyKey?: string) =>
    client.post<unknown>(`${ledger(pumpId)}/${clientId}/deposits/refund`, req, withKey(idempotencyKey)).then(r => r.data),

  /** Without an amount, writes off all the account owes. */
  writeOff: (pumpId: number, clientId: number, req: { amount?: number; reason: string }, idempotencyKey?: string) =>
    client.post<CreditAdjustment>(`${ledger(pumpId)}/${clientId}/write-off`, req, withKey(idempotencyKey)).then(r => r.data),

  closeAccount: (pumpId: number, clientId: number, reason: string) =>
    client.post<CreditClient>(`${ledger(pumpId)}/clients/${clientId}/close`, { reason }).then(r => r.data),

  reopenAccount: (pumpId: number, clientId: number) =>
    client.post<CreditClient>(`${ledger(pumpId)}/clients/${clientId}/reopen`).then(r => r.data),

  mergeInto: (pumpId: number, clientId: number, targetClientId: number) =>
    client.post<CreditClient>(`${ledger(pumpId)}/clients/${clientId}/merge-into/${targetClientId}`).then(r => r.data),

  raiseDispute: (pumpId: number, req: { debitType: CreditDebitType; debitId: number; reason: string }) =>
    client.post<unknown>(`${ledger(pumpId)}/disputes`, req).then(r => r.data),

  resolveDispute: (pumpId: number, disputeId: number,
                   req: { resolution: 'ACCEPTED' | 'CREDITED'; note: string; creditAmount?: number }, idempotencyKey?: string) =>
    client.post<unknown>(`${ledger(pumpId)}/disputes/${disputeId}/resolve`, req, withKey(idempotencyKey)).then(r => r.data),

  listGstCreditNotes: (pumpId: number, clientId: number) =>
    client.get<GstCreditNote[]>(`/pumps/${pumpId}/credit-invoices/credit-notes`, { params: { clientId } }).then(r => r.data),

  gstCreditNotePrintUrl: (pumpId: number, creditNoteId: number) =>
    `/api/pumps/${pumpId}/credit-invoices/credit-notes/${creditNoteId}/print`,
}
