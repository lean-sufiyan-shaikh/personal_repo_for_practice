import client from './client'
import type { PagedResponse } from '../types/paged'

/** CLOSED is closed and waiting for the owner's approval; a CLOSED or APPROVED day refuses changes. */
export type DayCloseStatus = 'CLOSED' | 'REOPENED' | 'APPROVED'

export type FlagColour = 'GREEN' | 'AMBER' | 'RED'

export type ChecklistCode =
  | 'SAFE_COUNT'
  | 'NEXT_DAY_PRICES'
  | 'OPEN_SHIFTS'
  | 'TANKS_WITHOUT_DIP'
  | 'PENDING_CASH_DROPS'
  | 'DELIVERIES_NOT_CHECKED'
  | 'OPEN_ATTENTION_ITEMS'
  | 'PENDING_EXPENSES'
  | 'PENDING_CREDIT_PAYMENTS'

export interface ChecklistItem {
  code: ChecklistCode
  blocking: boolean
  done: boolean
  params: Record<string, unknown>
  /** English text from the server, shown when the code has no translation. */
  text: string
  fixPath: string
}

export interface FlagReason {
  colour: FlagColour
  code: string
  params: Record<string, unknown>
  text: string
}

/** A section's colour is its worst reason; a GREEN section has one *_OK reason. */
export interface SectionFlag {
  colour: FlagColour
  reasons: FlagReason[]
}

export interface FuelSalesLine {
  fuelType: string
  litres: number
  revenue: number
  cost: number
  margin: number
}

export interface SalesSummary {
  flag: SectionFlag
  fuels: FuelSalesLine[]
  fuelLitres: number
  fuelRevenue: number
  fuelCost: number
  fuelMargin: number
  productRevenue: number
  productCost: number
  productMargin: number
  grossMargin: number
}

export interface SafeCountResult {
  countedAmount: number
  expectedAmount: number
  varianceAmount: number
  varianceType: 'SHORT' | 'OVER' | null
  toleranceAmount: number
  beyondTolerance: boolean
  countedAt: string
}

export interface CashSummary {
  flag: SectionFlag
  cash: number
  upi: number
  card: number
  fleetCard: number
  credit: number
  shortageAmount: number
  shortShiftCount: number
  overAmount: number
  overShiftCount: number
  confirmedDropCount: number
  confirmedDropAmount: number
  pendingDropCount: number
  safeCount: SafeCountResult | null
  depositCount: number
  depositAmount: number
}

export interface TankVariance {
  tankId: number
  tankIdentifier: string
  fuelType: string
  dipped: boolean
  varianceLitres: number | null
  variancePercent: number | null
  withinTolerance: boolean | null
}

export interface DeliveryCheck {
  deliveryId: number
  invoiceReference: string
  fuelType: string
  invoicedLitres: number
  receivedLitres: number | null
  varianceLitres: number | null
  checkStatus: 'NOT_CHECKED' | 'OK' | 'SHORT' | 'OVER'
}

export interface StockSummary {
  flag: SectionFlag
  tanks: TankVariance[]
  deliveries: DeliveryCheck[]
  openClaimCount: number
  openClaimAmount: number
}

export interface SummaryAttentionItem {
  type: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
  reason: string
  reasonCode: string
  reasonParams: Record<string, unknown>
}

export interface CreditSummary {
  flag: SectionFlag
  creditGiven: number
  collections: number
  overdueClientCount: number
  overdueAmount: number
  overLimitClientCount: number
  openAttentionCount: number
  attentionItems: SummaryAttentionItem[]
}

/** The owner's one-page view of a day: stored at close, or worked out live for the preview. */
export interface DaySummary {
  businessDate: string
  builtAt: string
  closedShiftCount: number
  openShiftCount: number
  overallFlag: FlagColour
  warnings: ChecklistItem[]
  sales: SalesSummary
  cash: CashSummary
  stock: StockSummary
  credit: CreditSummary
}

export interface DayClosePreview {
  businessDate: string
  status: DayCloseStatus | null
  canClose: boolean
  /** The two blocking checks always, then the warnings that apply. */
  checks: ChecklistItem[]
  summary: DaySummary
}

export interface DayCloseListItem {
  businessDate: string
  status: DayCloseStatus
  version: number
  closedByName: string
  closedAt: string
  approvedByName: string | null
  approvedAt: string | null
  overallFlag: FlagColour
  salesFlag: FlagColour
  cashFlag: FlagColour
  stockFlag: FlagColour
  creditFlag: FlagColour
  fuelRevenue: number
  grossMargin: number
  commentCount: number
}

export type DayCloseCommentKind = 'QUESTION' | 'REPLY' | 'REOPEN_REASON'

export interface DayCloseComment {
  id: number
  kind: DayCloseCommentKind
  message: string
  authorName: string
  createdAt: string
}

export interface DayCloseVersion {
  version: number
  closedByName: string
  closedAt: string
}

export interface DayCloseDetail {
  businessDate: string
  status: DayCloseStatus
  version: number
  closedByName: string
  closedAt: string
  approvedByName: string | null
  approvedAt: string | null
  balanceSheetId: number | null
  /** The day's balance sheet changed after the close. */
  balanceSheetStale: boolean
  summary: DaySummary
  versions: DayCloseVersion[]
  comments: DayCloseComment[]
}

const base = (pumpId: number) => `/pumps/${pumpId}/day-closes`

export const dayCloseApi = {
  getPreview: (pumpId: number, date: string): Promise<DayClosePreview> =>
    client.get(`${base(pumpId)}/preview`, { params: { date } }).then(r => r.data),

  list: (pumpId: number, page = 0, size = 10, status?: DayCloseStatus): Promise<PagedResponse<DayCloseListItem>> =>
    client.get(base(pumpId), { params: { page, size, ...(status && { status }) } }).then(r => r.data),

  getDetail: (pumpId: number, date: string): Promise<DayCloseDetail> =>
    client.get(`${base(pumpId)}/${date}`).then(r => r.data),

  getVersion: (pumpId: number, date: string, version: number): Promise<DaySummary> =>
    client.get(`${base(pumpId)}/${date}/versions/${version}`).then(r => r.data),

  close: (pumpId: number, date: string, approve: boolean): Promise<DayCloseDetail> =>
    client.post(`${base(pumpId)}/${date}/close`, { approve }).then(r => r.data),

  approve: (pumpId: number, date: string): Promise<DayCloseDetail> =>
    client.post(`${base(pumpId)}/${date}/approve`).then(r => r.data),

  addComment: (pumpId: number, date: string, message: string): Promise<DayCloseDetail> =>
    client.post(`${base(pumpId)}/${date}/comments`, { message }).then(r => r.data),

  reopen: (pumpId: number, date: string, reason: string): Promise<DayCloseDetail> =>
    client.post(`${base(pumpId)}/${date}/reopen`, { reason }).then(r => r.data),
}
