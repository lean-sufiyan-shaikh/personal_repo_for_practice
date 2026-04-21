import client from './client'
import type { PagedResponse } from '../types/paged'

// ── Types ──────────────────────────────────────────────────────────────────

export type SettlementPaymentType = 'UPI' | 'CARD' | 'FLEET_CARD'

export interface Settlement {
  id: number
  pumpId: number
  paymentType: SettlementPaymentType
  settlementDate: string   // YYYY-MM-DD
  amountReceived: number
  notes: string | null
  recordedByUserName: string
  createdAt: string
}

export interface SettlementConfig {
  id: number | null
  paymentType: SettlementPaymentType
  alertTime: string        // HH:mm
  enabled: boolean
}

export interface WalletSummary {
  upiPending: number
  cardPending: number
  fleetCardPending: number
  totalPending: number
}

export interface RecordSettlementRequest {
  paymentType: SettlementPaymentType
  settlementDate: string   // YYYY-MM-DD
  amountReceived: number
  notes?: string
}

export interface UpdateConfigRequest {
  alertTime: string        // HH:mm
  enabled: boolean
}

// ── API ──────────────────────────────────────────────────────────────────

export const paymentSettlementApi = {
  /** Paginated list of settlement records for a pump, newest first. Optionally filter by paymentType. */
  list: (pumpId: number, page = 0, size = 20, paymentType?: SettlementPaymentType): Promise<PagedResponse<Settlement>> => {
    const params: Record<string, string | number> = { page, size }
    if (paymentType) params.paymentType = paymentType
    return client.get(`/pumps/${pumpId}/settlements`, { params }).then(r => r.data)
  },

  /** Record a new settlement (amount received in bank). */
  record: (pumpId: number, data: RecordSettlementRequest): Promise<Settlement> =>
    client.post(`/pumps/${pumpId}/settlements`, data).then(r => r.data),

  /** Current wallet balance per payment type. */
  getWallet: (pumpId: number): Promise<WalletSummary> =>
    client.get(`/pumps/${pumpId}/settlements/wallet`).then(r => r.data),

  /** Alert configs for all three payment types. */
  getConfigs: (pumpId: number): Promise<SettlementConfig[]> =>
    client.get(`/pumps/${pumpId}/settlement-configs`).then(r => r.data),

  /** Create or update alert config for one payment type. */
  upsertConfig: (pumpId: number, paymentType: SettlementPaymentType, data: UpdateConfigRequest): Promise<SettlementConfig> =>
    client.put(`/pumps/${pumpId}/settlement-configs/${paymentType}`, data).then(r => r.data),
}
