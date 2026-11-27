import client from './client'

export interface StaffAdvance {
  id: number
  staffUserId: number
  staffName: string
  amount: number
  advanceDate: string
  note: string | null
  /** What payroll has taken back so far. */
  recovered: number
  /** Zero for a voided advance. */
  outstanding: number
  status: 'ACTIVE' | 'VOIDED'
  givenByName: string
  voidReason: string | null
  voidedAt: string | null
  createdAt: string
}

export interface StaffOutstanding {
  staffUserId: number
  staffName: string
  outstanding: number
}

export interface StaffAdvanceList {
  advances: StaffAdvance[]
  outstandingByStaff: StaffOutstanding[]
}

export interface GiveStaffAdvanceRequest {
  staffUserId: number
  amount: number
  advanceDate: string
  note?: string
}

/** Cash advances to staff: out of the safe when given, taken back from later payroll. */
export const staffAdvanceApi = {
  list: (pumpId: number) =>
    client.get<StaffAdvanceList>(`/pumps/${pumpId}/staff-advances`).then((r) => r.data),

  give: (pumpId: number, request: GiveStaffAdvanceRequest) =>
    client.post<StaffAdvance>(`/pumps/${pumpId}/staff-advances`, request).then((r) => r.data),

  void: (pumpId: number, advanceId: number, voidReason: string) =>
    client.patch<StaffAdvance>(`/pumps/${pumpId}/staff-advances/${advanceId}/void`, { voidReason }).then((r) => r.data),
}
