import client from './client'
import type { DUOption, Shift, OpenShiftRequest, CloseShiftRequest, CreditEntryInput, BackfillShiftRequest } from '../types/shift'

export type { CreditEntryInput }

export interface ResolveDiscrepancyRequest {
  resolutionAction: 'PENDING_INVESTIGATION' | 'SALARY_DEDUCTION' | 'CASH_RECOVERY' | 'WAIVED'
  resolutionNote?: string
}

export interface ShiftHistoryFilter {
  from?: string
  to?: string
  discrepanciesOnly?: boolean
  discrepancyType?: 'SHORT' | 'OVER'
}

const HISTORY_PAGE_SIZE = 200

export const shiftApi = {
  getActiveShifts: (pumpId: number) =>
    client.get<Shift[]>(`/pumps/${pumpId}/shifts/active`).then((r) => r.data),

  // Loads every shift matching the filter, page by page (the backend caps a page at 200 rows)
  getShiftHistory: async (pumpId: number, filter: ShiftHistoryFilter = {}): Promise<Shift[]> => {
    const all: Shift[] = []
    let page = 0
    let isLast = false
    while (!isLast) {
      const result = await client.get<{ content: Shift[]; last: boolean }>(`/pumps/${pumpId}/shifts/history`, {
        params: {
          page,
          size: HISTORY_PAGE_SIZE,
          ...(filter.from && { from: filter.from }),
          ...(filter.to && { to: filter.to }),
          ...(filter.discrepanciesOnly && { discrepanciesOnly: true }),
          ...(filter.discrepancyType && { discrepancyType: filter.discrepancyType }),
        },
      }).then((r) => r.data)
      all.push(...result.content)
      isLast = result.last
      page += 1
    }
    return all
  },

  getShift: (pumpId: number, id: number) =>
    client.get<Shift>(`/pumps/${pumpId}/shifts/${id}`).then((r) => r.data),

  openShift: (pumpId: number, request: OpenShiftRequest) =>
    client.post<Shift>(`/pumps/${pumpId}/shifts/open`, request).then((r) => r.data),

  addCreditEntry: (pumpId: number, shiftId: number, entry: CreditEntryInput) =>
    client.post<Shift>(`/pumps/${pumpId}/shifts/${shiftId}/credit-entries`, entry).then((r) => r.data),

  closeShift: (pumpId: number, shiftId: number, request: CloseShiftRequest) =>
    client.post<Shift>(`/pumps/${pumpId}/shifts/${shiftId}/close`, request).then((r) => r.data),

  getDUs: (pumpId: number) =>
    client.get<DUOption[]>(`/pumps/${pumpId}/dus`).then((r) => r.data),

  resolveDiscrepancy: (pumpId: number, shiftId: number, req: ResolveDiscrepancyRequest) =>
    client.patch<Shift>(`/pumps/${pumpId}/shifts/${shiftId}/discrepancy-resolution`, req).then((r) => r.data),

  voidCreditEntry: (pumpId: number, shiftId: number, entryId: number, voidReason: string) =>
    client.patch<Shift>(`/pumps/${pumpId}/shifts/${shiftId}/credit-entries/${entryId}/void`, { voidReason }).then((r) => r.data),

  backfillShift: (pumpId: number, request: BackfillShiftRequest) =>
    client.post<Shift>(`/pumps/${pumpId}/shifts/backfill`, request).then((r) => r.data),
}
