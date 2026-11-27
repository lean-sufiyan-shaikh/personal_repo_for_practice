import type { VehicleFlag } from '../api/vehicleApi'
import type { CashDenominations } from '../api/cashApi'
export type FuelType = 'PETROL' | 'SPEED_PETROL' | 'DIESEL' | 'SPEED_DIESEL' | 'CNG'

export type ShiftStatus =
  | 'OPEN'
  | 'OPEN_OVERDUE'
  | 'AUTO_CLOSED_OVERDUE'
  | 'CLOSED_BALANCED'
  | 'CLOSED_DISCREPANCY_PENDING'
  | 'CLOSED_DISCREPANCY_PENDING_APPROVAL'
  | 'CLOSED_DISCREPANCY_RESOLVED'

export type DiscrepancyType = 'SHORT' | 'OVER'

// ── Nozzle on a Dispensary Unit ───────────────────────────────────────────────

export interface NozzleDetail {
  id: number
  nozzleNumber: number
  fuelType: FuelType
  lastReading: number
  maxMeterValue: number
  tankId: number | null
  status: string
}

// ── Dispensary Unit (DU) — the physical MPD machine ──────────────────────────

export interface DUOption {
  id: number
  pumpId: number
  duNumber: number
  name: string
  status: string
  nozzles: NozzleDetail[]
}

// ── Shift fuel reading (one per nozzle) ────────────────────────────────────

/** Litres from one meter reading sold at one price; unitsSold is null until the shift closes. */
export interface PriceSegment {
  startReading: number
  pricePerUnit: number
  startsAt: string
  unitsSold: number | null
}

export interface FuelReading {
  nozzleId: number
  fuelType: FuelType
  startReading: number
  endReading: number | null
  priceSnapshot: number
  unitsSold: number | null
  maxMeterValue: number | null
  /** The litres priced per segment, set at close. */
  salesAmount?: number | null
  /** Litres sold beyond the tank's book stock, until a delivery covers them. */
  stockShortfallLitres?: number
  /** Empty unless the price changed while the shift was open. */
  priceSegments?: PriceSegment[]
}

export interface InvestigationNote {
  note: string
  writtenByName: string | null
  writtenAt: string
}

// ── Shift ──────────────────────────────────────────────────────────────────

export interface CreditEntry {
  id: number
  clientId: number | null
  clientName: string
  billNo: string | null
  amount: number
  fuelType: string | null
  description: string | null
  voidStatus: 'ACTIVE' | 'VOIDED'
  voidReason: string | null
  vehicleRegistration?: string | null
  driverName?: string | null
  /** The client's listed vehicle the entry was for; null for an unknown or unrecorded vehicle. */
  vehicleId?: number | null
  driverPhone?: string | null
  /** Why a WARN account's entry was let through outside its vehicle rules. */
  vehicleFlag?: VehicleFlag | null
}

export interface CreditEntryInput {
  clientName: string
  /** Preferred over name-based lookup when a sub-account is explicitly selected. */
  clientId?: number
  billNo?: string
  amount: number
  fuelType?: string
  description?: string
  /** A listed vehicle; preferred over vehicleRegistration. */
  vehicleId?: number
  vehicleRegistration?: string
  driverName?: string
  driverPhone?: string
}

export interface NozzleSummary {
  id: number
  nozzleNumber: number
  fuelType: string
}

export interface Shift {
  id: number
  pumpId: number
  duId: number
  duNumber: number | null
  duName: string | null
  nozzles: NozzleSummary[]
  operatorId: number
  operatorName: string
  openedByUserName: string
  shiftWindow: string
  shiftDate: string
  actualStartTime: string
  actualEndTime: string | null
  /** When the closing readings were really entered; actualEndTime is capped at the scheduled end. */
  closedAt?: string | null
  closedByUserId?: number | null
  /**
   * Scheduled end time from the shift's definition (IST, ISO string).
   * Null only for legacy shifts with no linked definition.
   * Used to show a late-close warning in the Close Shift modal.
   */
  scheduledEndTime: string | null
  fuelReadings: FuelReading[]
  totalAmountDue: number | null
  cashCollected: number | null
  upiCollected: number | null
  cardCollected: number | null
  fleetCardCollected: number | null
  creditTotal: number | null
  /** Confirmed cash drops at close, already part of cashCollected; null while open and for older shifts. */
  cashDropped: number | null
  /** The bag count by note at close; null when only a total was typed. */
  cashDenominations: CashDenominations | null
  discrepancyAmount: number | null
  discrepancyType: DiscrepancyType | null
  discrepancyReason: string | null
  discrepancyResolution: string | null
  discrepancyResolutionNote: string | null
  /** Set while (and after) a discrepancy was put under investigation; the shift stays pending until a final action. */
  investigationNote: string | null
  investigatedByName: string | null
  investigationStartedAt: string | null
  /** Every investigation note, oldest first. */
  investigationHistory?: InvestigationNote[]
  /** Cash handed back for a SHORT discrepancy, all of it or part; the rest stays pending. */
  cashRecoveryAmount: number | null
  cashRecoveredAt?: string | null
  /** Collected minus due absorbed as rounding when the shift closed balanced. */
  roundingAdjustment?: number | null
  /** The tanks' book stock could not cover what the shift sold; a delivery may be missing. */
  stockShortfall?: boolean
  status: ShiftStatus
  /** True when this shift was entered retroactively by Admin/Owner via the backfill flow. */
  isBackfilled: boolean
  creditEntries: CreditEntry[]
}

// ── API Request types ───────────────────────────────────────────────────────

export interface OpenShiftRequest {
  duId: number
  nozzleIds: number[]
  operatorId: number
  /** The chosen shift window and its business date; the server suggests one when absent. */
  shiftDefinitionId?: number
  shiftDate?: string
}

export interface CloseShiftRequest {
  fuelReadings: Array<{
    nozzleId: number
    endReading: number
  }>
  /** Cash counted in the bag; the server adds the confirmed drops to give the shift's cash. */
  cashCollected: number
  /** Sent only when the bag was counted note by note; must add up to cashCollected. */
  cashDenominations?: CashDenominations
  upiCollected: number
  cardCollected: number
  fleetCardCollected: number
  creditTotal: number
  creditEntries: CreditEntryInput[]
  /** Pre-recorded entries corrected in the close form; the backend voids them in the same transaction as the close. */
  replacedCreditEntryIds: number[]
  discrepancyReason?: string
  /** Owner/Admin only: lets credit for over-limit, on-hold or overdue clients through, with the reason recorded. */
  allowBlockedCredit?: boolean
  blockedCreditReason?: string
}

export interface CreateHandoverRequest {
  outgoingShiftId: number
  incomingOperatorId: number
  physicalCashVerified: boolean
  meterReadingsVerified: boolean
  notes?: string
  closing: CloseShiftRequest
}

export interface HandoverResponse {
  id: number
  pumpId: number
  outgoingShiftId: number
  outgoingOperatorId: number
  outgoingOperatorName: string
  incomingOperatorId: number
  incomingOperatorName: string
  incomingShiftId: number
  outgoingShiftStatus: ShiftStatus
  physicalCashVerified: boolean
  meterReadingsVerified: boolean
  notes: string | null
  handoverTime: string
  createdAt: string
}

export interface BackfillNozzleReading {
  nozzleId: number
  openingReading: number
  closingReading: number
}

export interface BackfillShiftRequest {
  shiftDefinitionId: number
  /** YYYY-MM-DD — must be within the last 365 days and before today */
  shiftDate: string
  duId: number
  operatorId: number
  nozzleReadings: BackfillNozzleReading[]
  cashCollected: number
  upiCollected: number
  cardCollected: number
  fleetCardCollected: number
  creditTotal: number
  creditEntries: CreditEntryInput[]
  discrepancyReason?: string
  /**
   * Historical fuel rates to save before processing when no price exists in DB for a fuel type
   * on shiftDate. Keys are FuelType enum names (e.g. "PETROL", "DIESEL"), values are ₹/L.
   */
  fuelRateOverrides?: Record<string, number>
}
