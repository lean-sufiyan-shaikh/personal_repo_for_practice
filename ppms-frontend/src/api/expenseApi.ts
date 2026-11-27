import client from './client'
import type { PagedResponse } from '../types/paged'

export type ExpenseCategory = 'FUEL' | 'MAINTENANCE' | 'SALARY' | 'UTILITIES' | 'EQUIPMENT' | 'OTHER'
export type ExpenseApprovalStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'VOIDED'
/** SAFE and PETTY_CASH move cash once the expense is approved; BANK and OWNER_POCKET move none. */
export type ExpensePaidFrom = 'SAFE' | 'PETTY_CASH' | 'BANK' | 'OWNER_POCKET'

export interface PumpExpense {
  id: number
  pumpId: number
  category: ExpenseCategory
  amount: number
  description: string
  expenseDate: string
  recordedByUserId: number
  approvalStatus: ExpenseApprovalStatus
  approvedByUserId: number | null
  approvedAt: string | null
  approvalNotes: string | null
  submittedByUserId: number | null
  submittedAt: string | null
  /** Null for expenses recorded before the source was asked for. */
  paidFrom: ExpensePaidFrom | null
  cashEventId: number | null
  voidReason: string | null
  voidedByUserId: number | null
  voidedAt: string | null
  createdAt: string
}

export interface CreateExpenseRequest {
  category: ExpenseCategory
  amount: number
  description: string
  expenseDate: string
  paidFrom: ExpensePaidFrom
  saveDraft?: boolean
}

export interface ApproveExpenseRequest {
  action: 'APPROVED' | 'REJECTED'
  notes?: string
}

export interface ExpenseSummary {
  approvedTotal: number
  pendingCount: number
  pendingTotal: number
}

const withIdempotencyKey = (idempotencyKey?: string) =>
  idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined

export const expenseApi = {
  getSummary: (pumpId: number): Promise<ExpenseSummary> =>
    client.get(`/pumps/${pumpId}/expenses/summary`).then(r => r.data),

  getExpenses: (
    pumpId: number,
    page = 0,
    size = 10,
    category?: ExpenseCategory,
    approvalStatus?: ExpenseApprovalStatus,
  ): Promise<PagedResponse<PumpExpense>> =>
    client.get(`/pumps/${pumpId}/expenses`, {
      params: { page, size, ...(category && { category }), ...(approvalStatus && { approvalStatus }) },
    }).then(r => r.data),

  createExpense: (pumpId: number, data: CreateExpenseRequest, idempotencyKey?: string): Promise<PumpExpense> =>
    client.post(`/pumps/${pumpId}/expenses`, data, withIdempotencyKey(idempotencyKey)).then(r => r.data),

  /** Owner/Admin: voids an approved expense and reverses the cash it took from the safe or petty cash. */
  voidExpense: (pumpId: number, expenseId: number, reason: string): Promise<PumpExpense> =>
    client.post(`/pumps/${pumpId}/expenses/${expenseId}/void`, { reason }).then(r => r.data),

  approveExpense: (pumpId: number, expenseId: number, data: ApproveExpenseRequest): Promise<PumpExpense> =>
    client.patch(`/pumps/${pumpId}/expenses/${expenseId}/approve`, data).then(r => r.data),

  submitExpense: (pumpId: number, expenseId: number): Promise<PumpExpense> =>
    client.patch(`/pumps/${pumpId}/expenses/${expenseId}/submit`).then(r => r.data),

  deleteExpense: (pumpId: number, expenseId: number): Promise<void> =>
    client.delete(`/pumps/${pumpId}/expenses/${expenseId}`).then(r => r.data),
}
