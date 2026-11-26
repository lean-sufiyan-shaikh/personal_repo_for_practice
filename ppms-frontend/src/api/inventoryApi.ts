import client from './client'
import type { PagedResponse } from '../types/paged'

export interface TankStock {
  tankId: number
  tankIdentifier: string
  fuelType: 'PETROL' | 'DIESEL' | 'CNG'
  capacity: number
  currentStock: number
  stockPercentage: number
  lowStock: boolean
  dipTolerance: number
  pumpId: number
}

export type ReceiptCheckStatus = 'NOT_CHECKED' | 'OK' | 'SHORT' | 'OVER'
export type ShortageClaimStatus = 'RAISED' | 'SETTLED' | 'REJECTED'

/** What a delivery's dips and meter readings say reached the tank. varianceLitres is received − invoiced: negative is short. */
export interface DeliveryReceiptCheck {
  dipBeforeLitres: number
  dipAfterLitres: number
  soldBetweenLitres: number
  receivedLitres: number
  varianceLitres: number
  variancePercent: number
  tolerancePercent: number
  observedDensity: number | null
  invoiceDensity: number | null
  densityDifference: number | null
  densityTolerance: number
}

/** An active nozzle drawing from one of the pump's tanks; lastReading prefills its reading before unloading. */
export interface ReceiptNozzle {
  nozzleId: number
  nozzleNumber: number
  duName: string | null
  tankId: number
  fuelType: string
  lastReading: number
  maxMeterValue: number
}

export interface ReceiptCheckRequest {
  dipBeforeLitres: number
  dipAfterLitres: number
  nozzleReadings: Array<{ nozzleId: number; readingBefore: number; readingAfter: number }>
  observedDensity?: number | null
  invoiceDensity?: number | null
}

export interface ShortageClaimNozzleReading {
  nozzleId: number
  nozzleNumber: number
  duName: string | null
  readingBefore: number
  readingAfter: number
  litresSold: number
}

export interface ShortageClaim {
  id: number
  deliveryId: number
  tankId: number
  tankIdentifier: string
  fuelType: string
  invoiceReference: string
  deliveryDate: string
  tankerName: string | null
  invoiceLitres: number
  receivedLitres: number
  shortLitres: number
  shortPercent: number
  tolerancePercent: number
  pricePerLitre: number
  claimAmount: number
  dipBeforeLitres: number
  dipAfterLitres: number
  soldBetweenLitres: number
  observedDensity: number | null
  invoiceDensity: number | null
  densityDifference: number | null
  densityTolerance: number
  densityFlagged: boolean
  status: ShortageClaimStatus
  raisedAt: string
  raisedByUserName: string
  creditNoteAmount: number | null
  creditNoteReference: string | null
  creditNoteDate: string | null
  rejectionReason: string | null
  resolvedAt: string | null
  resolvedByUserName: string | null
  /** Only filled when one claim is fetched. */
  nozzleReadings: ShortageClaimNozzleReading[] | null
  pump: { name: string; address: string; gstin: string | null; legalName: string | null } | null
}

/** Totals over every claim of the pump, whatever the status filter. */
export interface ShortageClaimTotals {
  openCount: number
  openAmount: number
  settledCount: number
  settledAmount: number
  rejectedCount: number
}

export interface ShortageClaimList {
  claims: PagedResponse<ShortageClaim>
  totals: ShortageClaimTotals
}

export interface SettleShortageClaimRequest {
  creditNoteAmount: number
  creditNoteReference: string
  creditNoteDate: string   // YYYY-MM-DD
}

export interface TankerDelivery {
  id: number
  pumpId: number
  tankId: number
  tankIdentifier: string
  fuelType: string
  quantityDelivered: number
  costPricePerUnit: number
  totalCost: number
  /** Actual bill amount as entered by user. Null if not provided. May differ from totalCost due to freight or other charges. */
  billTotal: number | null
  /** Name of the tanker that made this delivery. Null for legacy entries. */
  tankerName: string | null
  deliveryDate: string
  invoiceReference: string
  loggedByUserName: string
  createdAt: string
  checkStatus: ReceiptCheckStatus
  densityFlagged: boolean
  /** Null for deliveries recorded before receipt checks. */
  receiptCheck: DeliveryReceiptCheck | null
  shortageClaimId: number | null
  shortageClaimStatus: ShortageClaimStatus | null
}

export interface RecordDeliveryRequest {
  tankId: number
  quantityDelivered: number
  costPricePerUnit: number
  deliveryDate: string   // YYYY-MM-DD
  invoiceReference: string
  receiptCheck: ReceiptCheckRequest
}

export interface UpdateDeliveryRequest {
  quantityDelivered: number
  /** Omit to preserve the existing cost price (manager edits). */
  costPricePerUnit?: number | null
  deliveryDate: string   // YYYY-MM-DD
  invoiceReference: string
}

export interface RecordBatchDeliveryRequest {
  deliveryDate: string   // YYYY-MM-DD
  invoiceReference: string
  /** Actual bill total (optional). May include freight, taxes, or other charges beyond qty × cost. */
  billTotal?: number | null
  /** ID of the tanker (truck) that made this delivery. Required when tanker selection is enforced. */
  tankerId?: number | null
  items: Array<{
    tankId: number
    quantityDelivered: number
    /** Omit to inherit the last recorded price for this tank (manager entries). */
    costPricePerUnit?: number | null
    receiptCheck: ReceiptCheckRequest
  }>
}

export interface DipCheck {
  id: number
  tankId: number
  tankIdentifier: string
  fuelType: string
  measuredQuantity: number
  systemStock: number
  variance: number
  notes: string | null
  checkedAt: string
  loggedByUserName: string
  checkedByUserName: string | null
  createdAt: string
  status: 'WITHIN_TOLERANCE' | 'PENDING_REVIEW' | 'REVIEWED'
  reviewedAt: string | null
  reviewedByUserName: string | null
}

export interface InventoryLotDetail {
  id: number
  tankerDeliveryId: number | null
  deliveryDate: string
  /** Invoice / bill number from the tanker delivery. Null for DIP-adjustment lots. */
  invoiceReference: string | null
  costPricePerUnit: number
  remainingQuantity: number
  originalQuantity: number
  /** True when this lot came from a DIP upward adjustment — no invoice, cost = 0. */
  isDipAdjustment: boolean
  status: 'ACTIVE'
  createdAt: string
}

/** A nozzle drawing from the tank on a shift that is still open; a dip check needs its current meter reading. */
export interface OpenShiftNozzle {
  shiftId: number
  nozzleId: number
  nozzleNumber: number
  duName: string | null
  fuelType: string
  openingReading: number
  maxMeterValue: number
}

/** The check is always recorded for today, so no date is sent. */
export interface RecordDipCheckRequest {
  tankId: number
  measuredQuantity: number
  checkedByUserId?: number
  notes?: string
  nozzleReadings: Array<{ nozzleId: number; meterReading: number }>
}

/** A stretch ending on a day with a dip; days without a dip are merged into it. NEEDS_OPENING_DIP has only closingLitres. */
export interface ReconciliationPeriod {
  periodStart: string
  periodEnd: string
  days: number
  status: 'RECONCILED' | 'NEEDS_OPENING_DIP'
  openingLitres: number | null
  deliveredLitres: number | null
  soldLitres: number | null
  removedLitres: number | null
  expectedClosingLitres: number | null
  closingLitres: number
  /** Closing dip minus expected closing: negative is a loss. */
  varianceLitres: number | null
  /** Variance as a share of litres sold; null when nothing was sold. */
  variancePercent: number | null
  withinTolerance: boolean | null
  cumulativeVarianceLitres: number | null
  cumulativeVariancePercent: number | null
}

export type ReconciliationStatus = 'WITHIN_LIMITS' | 'LOSS_ABOVE_LIMIT' | 'GAIN_ABOVE_LIMIT' | 'NO_DATA'

export interface TankReconciliation {
  tankId: number
  tankIdentifier: string
  fuelType: string
  dipTolerance: number
  lossAlertPercent: number
  summary: {
    salesLitres: number
    varianceLitres: number
    variancePercent: number | null
    reconciledPeriodCount: number
    flaggedPeriodCount: number
    status: ReconciliationStatus
  }
  periods: ReconciliationPeriod[]
}

export interface WetStockReconciliation {
  from: string
  to: string
  tanks: TankReconciliation[]
}

export const inventoryApi = {
  getTankStocks: (pumpId: number) =>
    client.get<TankStock[]>(`/inventory/${pumpId}/tanks`).then(r => r.data),

  recordDelivery: (pumpId: number, req: RecordDeliveryRequest) =>
    client.post<TankerDelivery>(`/inventory/${pumpId}/deliveries`, req).then(r => r.data),

  recordBatchDelivery: (pumpId: number, req: RecordBatchDeliveryRequest) =>
    client.post<TankerDelivery[]>(`/inventory/${pumpId}/deliveries/batch`, req).then(r => r.data),

  getDeliveries: (pumpId: number, page = 0, size = 10) =>
    client.get<PagedResponse<TankerDelivery>>(`/inventory/${pumpId}/deliveries`, { params: { page, size } }).then(r => r.data),

  updateDelivery: (pumpId: number, deliveryId: number, req: UpdateDeliveryRequest) =>
    client.patch<TankerDelivery>(`/inventory/${pumpId}/deliveries/${deliveryId}`, req).then(r => r.data),

  recordDipCheck: (pumpId: number, req: RecordDipCheckRequest) =>
    client.post<DipCheck>(`/inventory/${pumpId}/dip-checks`, req).then(r => r.data),

  getDipChecks: (pumpId: number, page = 0, size = 10, filters: { tankId?: number; fuelType?: string } = {}) =>
    client.get<PagedResponse<DipCheck>>(`/inventory/${pumpId}/dip-checks`, { params: { page, size, ...filters } }).then(r => r.data),

  reviewDipCheck: (pumpId: number, dipCheckId: number) =>
    client.patch<DipCheck>(`/inventory/${pumpId}/dip-checks/${dipCheckId}/review`).then(r => r.data),

  getActiveTankLots: (pumpId: number, tankId: number): Promise<InventoryLotDetail[]> =>
    client.get(`/inventory/${pumpId}/tanks/${tankId}/lots`).then(r => r.data),

  getOpenShiftNozzles: (pumpId: number, tankId: number) =>
    client.get<OpenShiftNozzle[]>(`/inventory/${pumpId}/tanks/${tankId}/open-shift-nozzles`).then(r => r.data),

  /** Tank lines of the most recently entered invoice — the only one that can be deleted. */
  getLatestInvoiceLines: (pumpId: number) =>
    client.get<TankerDelivery[]>(`/inventory/${pumpId}/deliveries/latest`).then(r => r.data),

  deleteLatestDelivery: (pumpId: number, invoiceReference: string) =>
    client.delete(`/inventory/${pumpId}/deliveries/latest`, { params: { invoiceReference } }),

  getReceiptNozzles: (pumpId: number) =>
    client.get<ReceiptNozzle[]>(`/inventory/${pumpId}/receipt-nozzles`).then(r => r.data),

  getShortageClaims: (pumpId: number, status: ShortageClaimStatus | undefined, page = 0, size = 20) =>
    client.get<ShortageClaimList>(`/inventory/${pumpId}/shortage-claims`, { params: { status, page, size } }).then(r => r.data),

  getShortageClaim: (pumpId: number, claimId: number) =>
    client.get<ShortageClaim>(`/inventory/${pumpId}/shortage-claims/${claimId}`).then(r => r.data),

  settleShortageClaim: (pumpId: number, claimId: number, req: SettleShortageClaimRequest) =>
    client.patch<ShortageClaim>(`/inventory/${pumpId}/shortage-claims/${claimId}/settle`, req).then(r => r.data),

  rejectShortageClaim: (pumpId: number, claimId: number, reason: string) =>
    client.patch<ShortageClaim>(`/inventory/${pumpId}/shortage-claims/${claimId}/reject`, { reason }).then(r => r.data),

  getReconciliation: (pumpId: number, from: string, to: string, tankId?: number) =>
    client.get<WetStockReconciliation>(`/inventory/${pumpId}/reconciliation`, { params: { from, to, tankId } }).then(r => r.data),
}
