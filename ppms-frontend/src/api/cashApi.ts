import client from './client'
import type { PagedResponse } from '../types/paged'

export type CashEventType = 'OPENING_BALANCE' | 'CASH_IN' | 'CASH_OUT' | 'CLOSING_BALANCE' | 'CASH_DROP' | 'BANK_DEPOSIT'

/** The types a person may record by hand; drops and bank deposits come from their own flows. */
export type ManualCashEventType = Exclude<CashEventType, 'CASH_DROP' | 'BANK_DEPOSIT'>

export interface CashEvent {
  id: number
  pumpId: number
  eventType: CashEventType
  amount: number
  description: string
  eventDate: string
  recordedByUserId: number
  createdAt: string
}

export interface CashDrawerResponse {
  events: PagedResponse<CashEvent>
  currentBalance: number
}

export interface RecordCashEventRequest {
  eventType: ManualCashEventType
  amount: number
  description: string
  eventDate: string
}

/** Notes counted by value, plus all coins as one amount in rupees. */
export interface CashDenominations {
  notes500: number
  notes200: number
  notes100: number
  notes50: number
  notes20: number
  notes10: number
  coins: number
}

export type CashDropStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export interface CashDrop {
  id: number
  shiftId: number
  operatorId: number
  operatorName: string
  amount: number
  denominations: CashDenominations | null
  status: CashDropStatus
  recordedByName: string
  recordedAt: string
  reviewedByName: string | null
  reviewedAt: string | null
  rejectionReason: string | null
}

export interface RecordCashDropRequest {
  amount: number
  denominations?: CashDenominations
}

export type VarianceType = 'SHORT' | 'OVER'

export interface SafeCount {
  id: number
  countedAmount: number
  expectedAmount: number
  /** Counted minus expected: negative when the safe is short. */
  varianceAmount: number
  varianceType: VarianceType | null
  toleranceAmount: number
  beyondTolerance: boolean
  denominations: CashDenominations | null
  notes: string | null
  countedByName: string
  countedAt: string
}

export interface SafeSummary {
  balance: number
  pendingDropCount: number
  pendingDropAmount: number
  countTolerance: number
  latestCount: SafeCount | null
}

export interface SafeLedgerEntry {
  id: number
  eventType: CashEventType
  /** Negative for money going out of the safe. */
  amount: number
  balanceAfter: number
  description: string
  eventDate: string
  createdAt: string
}

export interface RecordSafeCountRequest {
  countedAmount: number
  denominations?: CashDenominations
  notes?: string
}

export interface BankDeposit {
  id: number
  amount: number
  bankAccount: string
  depositDate: string
  slipReference: string
  notes: string | null
  hasSlipPhoto: boolean
  recordedByName: string
  createdAt: string
}

export interface BankDepositList {
  deposits: PagedResponse<BankDeposit>
  totalCount: number
  totalAmount: number
}

export interface RecordBankDepositRequest {
  amount: number
  bankAccount: string
  depositDate: string
  slipReference: string
  notes?: string
}

export interface DateRangeFilter {
  from?: string
  to?: string
}

const withIdempotencyKey = (idempotencyKey?: string) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

export const cashApi = {
  getCashEvents: (
    pumpId: number,
    page = 0,
    size = 10,
    eventType?: CashEventType,
    from?: string,
    to?: string,
  ): Promise<CashDrawerResponse> =>
    client.get(`/pumps/${pumpId}/cash-events`, {
      params: { page, size, ...(eventType && { eventType }), ...(from && { from }), ...(to && { to }) },
    }).then(r => r.data),

  recordCashEvent: (pumpId: number, data: RecordCashEventRequest): Promise<CashEvent> =>
    client.post(`/pumps/${pumpId}/cash-events`, data).then(r => r.data),

  getShiftDrops: (pumpId: number, shiftId: number): Promise<CashDrop[]> =>
    client.get(`/pumps/${pumpId}/shifts/${shiftId}/cash-drops`).then(r => r.data),

  recordDrop: (pumpId: number, shiftId: number, request: RecordCashDropRequest, idempotencyKey?: string): Promise<CashDrop> =>
    client.post(`/pumps/${pumpId}/shifts/${shiftId}/cash-drops`, request, withIdempotencyKey(idempotencyKey)).then(r => r.data),

  getPendingDrops: (pumpId: number): Promise<CashDrop[]> =>
    client.get(`/pumps/${pumpId}/cash-drops/pending`).then(r => r.data),

  confirmDrop: (pumpId: number, dropId: number): Promise<CashDrop> =>
    client.patch(`/pumps/${pumpId}/cash-drops/${dropId}/confirm`).then(r => r.data),

  rejectDrop: (pumpId: number, dropId: number, reason: string): Promise<CashDrop> =>
    client.patch(`/pumps/${pumpId}/cash-drops/${dropId}/reject`, { reason }).then(r => r.data),

  getSafe: (pumpId: number): Promise<SafeSummary> =>
    client.get(`/pumps/${pumpId}/safe`).then(r => r.data),

  getSafeLedger: (pumpId: number, page: number, size: number, range: DateRangeFilter = {}): Promise<PagedResponse<SafeLedgerEntry>> =>
    client.get(`/pumps/${pumpId}/safe/ledger`, { params: { page, size, ...range } }).then(r => r.data),

  getSafeCounts: (pumpId: number, page = 0, size = 10): Promise<PagedResponse<SafeCount>> =>
    client.get(`/pumps/${pumpId}/safe/counts`, { params: { page, size } }).then(r => r.data),

  recordSafeCount: (pumpId: number, request: RecordSafeCountRequest): Promise<SafeCount> =>
    client.post(`/pumps/${pumpId}/safe/counts`, request).then(r => r.data),

  getDeposits: (pumpId: number, page: number, size: number, filter: DateRangeFilter & { bankAccount?: string } = {}): Promise<BankDepositList> =>
    client.get(`/pumps/${pumpId}/safe/deposits`, { params: { page, size, ...filter } }).then(r => r.data),

  recordDeposit: (pumpId: number, request: RecordBankDepositRequest, idempotencyKey?: string): Promise<BankDeposit> =>
    client.post(`/pumps/${pumpId}/safe/deposits`, request, withIdempotencyKey(idempotencyKey)).then(r => r.data),

  attachDepositSlip: (pumpId: number, depositId: number, file: File): Promise<BankDeposit> => {
    const form = new FormData()
    form.append('file', file)
    // The client's JSON default would make axios turn the form into JSON; the browser adds the boundary itself.
    return client.put(`/pumps/${pumpId}/safe/deposits/${depositId}/slip`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },

  getDepositSlip: (pumpId: number, depositId: number): Promise<Blob> =>
    client.get(`/pumps/${pumpId}/safe/deposits/${depositId}/slip`, { responseType: 'blob' }).then(r => r.data),
}
