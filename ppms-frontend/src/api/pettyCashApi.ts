import client from './client'
import type { PagedResponse } from '../types/paged'

/** TOP_UP brings cash in from the safe, EXPENSE pays an expense out, REVERSAL cancels an expense's draw. */
export type PettyCashEntryType = 'TOP_UP' | 'EXPENSE' | 'REVERSAL'

export interface PettyCashCount {
  id: number
  countForDate: string
  countedAmount: number
  expectedAmount: number
  /** Counted minus expected: negative is short, positive is over. */
  varianceAmount: number
  toleranceAmount: number
  beyondTolerance: boolean
  notes: string | null
  countedByName: string
  countedAt: string
}

export interface PettyCashSummary {
  floatAmount: number
  countTolerance: number
  balance: number
  /** What brings the box back to its float; zero when it holds the float or more. */
  suggestedTopUp: number
  latestCount: PettyCashCount | null
}

export interface PettyCashEntry {
  id: number
  entryType: PettyCashEntryType
  /** Positive for money in, negative for money out. */
  amount: number
  balanceAfter: number
  description: string
  entryDate: string
  expenseId: number | null
  reversed: boolean
  createdAt: string
}

export interface PettyCashSettingsRequest {
  floatAmount: number
  countTolerance: number
}

export interface PettyCashTopUpRequest {
  amount: number
  entryDate?: string
  notes?: string
}

export interface PettyCashCountRequest {
  countedAmount: number
  countForDate?: string
  notes?: string
}

const withIdempotencyKey = (idempotencyKey?: string) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

export const pettyCashApi = {
  getSummary: (pumpId: number): Promise<PettyCashSummary> =>
    client.get(`/pumps/${pumpId}/petty-cash`).then(r => r.data),

  getLedger: (pumpId: number, page = 0, size = 10, range: { from?: string; to?: string } = {}): Promise<PagedResponse<PettyCashEntry>> =>
    client.get(`/pumps/${pumpId}/petty-cash/ledger`, { params: { page, size, ...range } }).then(r => r.data),

  getCounts: (pumpId: number, page = 0, size = 10): Promise<PagedResponse<PettyCashCount>> =>
    client.get(`/pumps/${pumpId}/petty-cash/counts`, { params: { page, size } }).then(r => r.data),

  /** Owner/Admin: the float the box is topped up to and how far a count may differ before the owner is told. */
  updateSettings: (pumpId: number, request: PettyCashSettingsRequest): Promise<PettyCashSummary> =>
    client.put(`/pumps/${pumpId}/petty-cash/settings`, request).then(r => r.data),

  /** Takes the cash out of the safe and puts it in the box together. */
  topUp: (pumpId: number, request: PettyCashTopUpRequest, idempotencyKey?: string): Promise<PettyCashEntry> =>
    client.post(`/pumps/${pumpId}/petty-cash/top-ups`, request, withIdempotencyKey(idempotencyKey)).then(r => r.data),

  recordCount: (pumpId: number, request: PettyCashCountRequest, idempotencyKey?: string): Promise<PettyCashCount> =>
    client.post(`/pumps/${pumpId}/petty-cash/counts`, request, withIdempotencyKey(idempotencyKey)).then(r => r.data),
}
