import client from './client'
import type { PagedResponse } from '../types/paged'

export type MonthCloseStatus = 'CLOSED' | 'REOPENED'
/** The day-close state of one business day; NOT_CLOSED when the day was never closed. */
export type MonthDayStatus = 'NOT_CLOSED' | 'CLOSED' | 'REOPENED' | 'APPROVED'

export interface MonthDay {
  date: string
  statusCode: MonthDayStatus
  fixPath: string
}

export interface MonthSummaryFuel {
  fuelType: string
  litres: number
  revenue: number
  cost: number
  margin: number
}

export interface MonthSummaryTank {
  tankId: number
  tankIdentifier: string
  fuelType: string
  lastDipDate: string | null
  closingLitres: number | null
  monthVarianceLitres: number | null
}

/** The month as stored at its close; otherCosts holds any further operating cost the P&L deducts. */
export interface MonthSummary {
  month: string
  fromDate: string
  toDate: string
  builtAt: string
  businessDayCount: number
  approvedDayCount: number
  exceptions: { date: string; statusCode: MonthDayStatus; reason: string }[]
  fuels: MonthSummaryFuel[]
  fuelLitres: number
  fuelRevenue: number
  fuelCost: number
  fuelMargin: number
  productRevenue: number
  productCost: number
  productMargin: number
  payments: { cash: number; upi: number; card: number; fleetCard: number; credit: number; total: number }
  grossProfit: number
  costs: { expenses: number; payroll: number; bankCharges: number; chargebacks: number; badDebt: number; otherCosts: number; total: number }
  netProfit: number
  credit: { given: number; collected: number; outstandingAtClose: number }
  safeClosing: number
  pettyCashClosing: number
  tanks: MonthSummaryTank[]
}

export interface MonthCloseVersion {
  version: number
  closedByName: string
  closedAt: string
  reopenedByName: string | null
  reopenedAt: string | null
  reopenReason: string | null
}

export interface MonthCloseDetail {
  month: string
  fromDate: string
  toDate: string
  monthOver: boolean
  status: MonthCloseStatus | null
  currentVersion: number | null
  days: MonthDay[]
  notReadyDayCount: number
  summary: MonthSummary | null
  versions: MonthCloseVersion[]
}

export interface MonthCloseListItem {
  month: string
  status: MonthCloseStatus
  currentVersion: number
}

export interface MonthException {
  date: string
  reason: string
}

export const monthCloseApi = {
  list: (pumpId: number, page = 0, size = 12): Promise<PagedResponse<MonthCloseListItem>> =>
    client.get(`/pumps/${pumpId}/month-closes`, { params: { page, size } }).then(r => r.data),

  /** month is YYYY-MM. */
  getDetail: (pumpId: number, month: string): Promise<MonthCloseDetail> =>
    client.get(`/pumps/${pumpId}/month-closes/${month}`).then(r => r.data),

  getVersion: (pumpId: number, month: string, version: number): Promise<MonthSummary> =>
    client.get(`/pumps/${pumpId}/month-closes/${month}/versions/${version}`).then(r => r.data),

  close: (pumpId: number, month: string, exceptions: MonthException[]): Promise<MonthCloseDetail> =>
    client.post(`/pumps/${pumpId}/month-closes/${month}/close`, { exceptions }).then(r => r.data),

  /** Owner only. */
  reopen: (pumpId: number, month: string, reason: string): Promise<MonthCloseDetail> =>
    client.post(`/pumps/${pumpId}/month-closes/${month}/reopen`, { reason }).then(r => r.data),
}
