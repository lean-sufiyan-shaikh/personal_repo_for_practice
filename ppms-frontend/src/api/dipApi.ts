import client from './client'

export type AdjustmentType = 'RESET' | 'CUSTOM_READING'

export interface NozzleReadingAdjustment {
  id: number
  pumpId: number
  nozzleId: number
  adjustmentType: AdjustmentType
  fuelType: string
  previousReading: number
  newReading: number
  reason: string
  recordedByUserId: number
  createdAt: string
  /** PENDING_APPROVAL: a manager's forward adjustment waiting for an Owner or Admin; the nozzle is unchanged until then. */
  status: MeterAdjustmentStatus
  /** Litres a forward adjustment found dispensed outside any shift; null for other adjustments. */
  unbilledLitres: number | null
  /** Those litres at the fuel price when the adjustment was asked for. */
  unbilledValue: number | null
  pricePerLitre: number | null
  reviewedByUserId: number | null
  reviewedAt: string | null
  reviewReason: string | null
}

export type MeterAdjustmentStatus = 'APPLIED' | 'PENDING_APPROVAL' | 'REJECTED'

export interface FuelDipEntry {
  id: number
  pumpId: number
  /** Null on entries recorded before drained fuel left tank stock. */
  tankId: number | null
  fuelType: string
  litresRemoved: number
  /** Average purchase cost per litre of the fuel drawn (selling price on older entries). */
  pricePerUnit: number
  /** Purchase cost of the fuel lost (selling value on older entries). */
  monetaryLoss: number
  reason: string
  dipDate: string       // YYYY-MM-DD
  recordedByUserId: number
  createdAt: string
}

export const dipApi = {

  // ── Meter reading adjustments ─────────────────────────────────────────────

  recordAdjustment: (pumpId: number, nozzleId: number, adjustmentType: AdjustmentType, reason: string, newReading?: number) =>
    client.post<NozzleReadingAdjustment>(
      `/pumps/${pumpId}/nozzles/${nozzleId}/reading-adjustments`,
      { adjustmentType, reason, newReading }
    ).then(r => r.data),

  getAdjustments: (pumpId: number, nozzleId: number) =>
    client.get<NozzleReadingAdjustment[]>(`/pumps/${pumpId}/nozzles/${nozzleId}/reading-adjustments`).then(r => r.data),

  // ── Fuel dip entries ──────────────────────────────────────────────────────

  recordDip: (pumpId: number, tankId: number, litresRemoved: number, reason: string, dipDate?: string) =>
    client.post<FuelDipEntry>(
      `/pumps/${pumpId}/fuel-dips`,
      { tankId, litresRemoved, reason, dipDate }
    ).then(r => r.data),

  getDips: (pumpId: number) =>
    client.get<FuelDipEntry[]>(`/pumps/${pumpId}/fuel-dips`).then(r => r.data),
}
