import type { ChecklistItem, DayCloseDetail, DayCloseListItem, DayClosePreview, DaySummary } from '../../api/dayCloseApi'

export const safeCountDone: ChecklistItem = {
  code: 'SAFE_COUNT', blocking: true, done: true, params: {}, text: 'Safe counted', fixPath: '/dashboard/cash?tab=safe',
}
export const safeCountMissing: ChecklistItem = { ...safeCountDone, done: false, text: 'No safe count' }
export const pricesDone: ChecklistItem = {
  code: 'NEXT_DAY_PRICES', blocking: true, done: true, params: { fuelTypeCodes: [] }, text: 'Prices set', fixPath: '/dashboard/setup?section=prices',
}
export const pricesMissing: ChecklistItem = { ...pricesDone, done: false, params: { fuelTypeCodes: ['PETROL', 'DIESEL'] }, text: 'Prices missing' }
export const openShiftsWarning: ChecklistItem = {
  code: 'OPEN_SHIFTS', blocking: false, done: false, params: { count: 2 }, text: '2 shifts open', fixPath: '/dashboard/shifts',
}

export const summary: DaySummary = {
  businessDate: '2026-11-26',
  builtAt: '2026-11-27T04:30:00Z',
  closedShiftCount: 3,
  openShiftCount: 0,
  overallFlag: 'AMBER',
  warnings: [],
  sales: {
    flag: { colour: 'GREEN', reasons: [{ colour: 'GREEN', code: 'SALES_OK', params: { shiftCount: 3 }, text: 'All 3 shifts closed' }] },
    fuels: [{ fuelType: 'PETROL', litres: 1200.5, revenue: 120050, cost: 108000, margin: 12050 }],
    fuelLitres: 1200.5, fuelRevenue: 120050, fuelCost: 108000, fuelMargin: 12050,
    productRevenue: 500, productCost: 400, productMargin: 100, grossMargin: 12150,
  },
  cash: {
    flag: { colour: 'AMBER', reasons: [{ colour: 'AMBER', code: 'CASH_SHIFT_SHORTAGES', params: { count: 1, amount: '150.00' }, text: '1 shift short' }] },
    cash: 80000, upi: 30000, card: 5000, fleetCard: 0, credit: 5050,
    shortageAmount: 150, shortShiftCount: 1, overAmount: 0, overShiftCount: 0,
    confirmedDropCount: 2, confirmedDropAmount: 20000, pendingDropCount: 0,
    safeCount: {
      countedAmount: 50000, expectedAmount: 50000, varianceAmount: 0, varianceType: null, toleranceAmount: 10,
      beyondTolerance: false, countedAt: '2026-11-26T15:00:00Z',
    },
    depositCount: 1, depositAmount: 40000,
  },
  stock: {
    flag: { colour: 'RED', reasons: [{ colour: 'RED', code: 'STOCK_TANK_OUTSIDE_TOLERANCE', params: { tankIdentifier: 'T-1', litres: '-80.000' }, text: 'Tank T-1 off' }] },
    tanks: [{ tankId: 1, tankIdentifier: 'T-1', fuelType: 'PETROL', dipped: true, varianceLitres: -80, variancePercent: -6.66, withinTolerance: false }],
    deliveries: [{ deliveryId: 4, invoiceReference: 'INV-9', fuelType: 'PETROL', invoicedLitres: 9000, receivedLitres: 9000, varianceLitres: 0, checkStatus: 'OK' }],
    openClaimCount: 0, openClaimAmount: 0,
  },
  credit: {
    flag: { colour: 'GREEN', reasons: [{ colour: 'GREEN', code: 'CREDIT_OK', params: {}, text: 'No overdue credit' }] },
    creditGiven: 5050, collections: 2000, overdueClientCount: 0, overdueAmount: 0, overLimitClientCount: 0,
    openAttentionCount: 0, attentionItems: [],
  },
}

export function preview(overrides: Partial<DayClosePreview> = {}): DayClosePreview {
  return {
    businessDate: '2026-11-26',
    status: null,
    canClose: true,
    checks: [safeCountDone, pricesDone],
    summary,
    ...overrides,
  }
}

export function detail(overrides: Partial<DayCloseDetail> = {}): DayCloseDetail {
  return {
    businessDate: '2026-11-26',
    status: 'CLOSED',
    version: 1,
    closedByName: 'Meena',
    closedAt: '2026-11-27T04:35:00Z',
    approvedByName: null,
    approvedAt: null,
    balanceSheetId: 42,
    balanceSheetStale: false,
    summary,
    versions: [{ version: 1, closedByName: 'Meena', closedAt: '2026-11-27T04:35:00Z' }],
    comments: [],
    ...overrides,
  }
}

export function listItem(overrides: Partial<DayCloseListItem> = {}): DayCloseListItem {
  return {
    businessDate: '2026-11-26',
    status: 'CLOSED',
    version: 1,
    closedByName: 'Meena',
    closedAt: '2026-11-27T04:35:00Z',
    approvedByName: null,
    approvedAt: null,
    overallFlag: 'RED',
    salesFlag: 'GREEN',
    cashFlag: 'AMBER',
    stockFlag: 'RED',
    creditFlag: 'GREEN',
    fuelRevenue: 120050,
    grossMargin: 12150,
    commentCount: 0,
    ...overrides,
  }
}

export function page<T>(content: T[]) {
  return { content, page: 0, pageSize: 10, totalElements: content.length, totalPages: content.length ? 1 : 0, hasNext: false, hasPrevious: false }
}
