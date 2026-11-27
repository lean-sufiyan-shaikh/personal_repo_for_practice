import client from './client'

export type AuditAction =
  // User management
  | 'USER_CREATED'
  | 'USER_UPDATED'
  | 'USER_DEACTIVATED'
  | 'USER_STATUS_CHANGED'
  | 'PASSWORD_RESET'
  | 'USER_PUMP_CHANGED'
  // Authentication
  | 'LOGIN'
  | 'LOGIN_FAILED'
  | 'TOKEN_REVOKED'
  // Fuel pricing
  | 'FUEL_PRICE_UPDATED'
  // Shift lifecycle
  | 'SHIFT_OPENED'
  | 'SHIFT_CLOSED'
  | 'SHIFT_BACKFILLED'
  | 'SHIFT_AMENDED'
  | 'DISCREPANCY_RESOLVED'
  | 'DISCREPANCY_INVESTIGATION_STARTED'
  // Credit management
  | 'CREDIT_ENTRY_VOIDED'
  | 'CREDIT_LIMIT_CHANGED'
  | 'CREDIT_PAYMENT_RECEIVED'
  | 'INTEREST_APPLIED'
  | 'INTEREST_CHARGE_WAIVED'
  | 'CREDIT_PAYMENT_REVERSED'
  | 'INTEREST_SETTINGS_CHANGED'
  | 'CREDIT_CLIENT_CREATED'
  | 'CREDIT_CLIENT_DELETED'
  | 'CREDIT_ENTRY_REASSIGNED'
  | 'CREDIT_INVOICE_ISSUED'
  | 'CREDIT_INVOICE_CANCELLED'
  | 'CREDIT_EXTENSION_GRANTED'
  | 'CREDIT_EXTENSION_REVOKED'
  | 'INTEREST_RECALCULATED'
  // Inventory
  | 'DELIVERY_RECORDED'
  | 'DELIVERY_UPDATED'
  | 'DELIVERY_DELETED'
  | 'LOT_COST_CORRECTED'
  | 'SHORTAGE_CLAIM_RAISED'
  | 'SHORTAGE_CLAIM_UPDATED'
  | 'SHORTAGE_CLAIM_SETTLED'
  | 'SHORTAGE_CLAIM_REJECTED'
  | 'SHORTAGE_CLAIM_WITHDRAWN'
  | 'DIP_CHECK_RECORDED'
  // Documents
  | 'DOCUMENT_ADDED'
  // Expenses
  | 'EXPENSE_RECORDED'
  | 'EXPENSE_APPROVED'
  | 'EXPENSE_REJECTED'
  // Payroll
  | 'PAYROLL_APPROVED'
  | 'PAYROLL_DELETED'
  | 'PAYROLL_PAID'
  | 'PAYROLL_RETURNED_TO_DRAFT'
  // Pump management
  | 'PUMP_CREATED'
  | 'PUMP_DELETED'
  | 'PUMP_UPDATED'
  | 'PUMP_SUSPENDED'
  | 'PUMP_ENABLED'
  | 'OWNER_ONBOARDED'
  | 'NOZZLE_ADDED'
  | 'CALIBRATION_LOGGED'
  | 'TANK_ADDED'
  // Ancillary products
  | 'ANCILLARY_SALE_RECORDED'
  | 'ANCILLARY_PRICE_OVERRIDDEN'
  | 'ANCILLARY_SALE_BACKFILLED'
  | 'ANCILLARY_DELIVERY_RECORDED'
  | 'ANCILLARY_DELIVERY_BACKFILLED'
  // Operations
  | 'HANDOVER_COMPLETED'
  | 'PUMP_CLOSURE_ADDED'
  | 'BANK_STATEMENT_IMPORTED'
  | 'BANK_LINE_MATCHED'
  | 'BANK_LINE_UNMATCHED'
  | 'BANK_LINE_IGNORED'
  | 'BANK_LINES_AUTO_MATCHED'
  | 'SUPPLIER_PAYMENT_RECORDED'
  | 'SUPPLIER_PAYMENT_VOIDED'
  | 'SUPPLIER_OPENING_BALANCE_SET'
  | 'SUPPLIER_DEFAULT_SET'
  | 'TEST_LITRES_ABOVE_MAX'
  | 'PUMP_CONFIG_UPDATED'
  // Payment settlements
  | 'SETTLEMENT_RECORDED'
  | 'SETTLEMENT_VOIDED'
  | 'SETTLEMENT_CONFIG_UPDATED'
  | 'CASH_DROP_RECORDED'
  | 'CASH_DROP_CONFIRMED'
  | 'CASH_DROP_REJECTED'
  | 'CASH_EVENT_RECORDED'
  | 'CASH_EVENT_REVERSED'
  | 'SAFE_COUNTED'
  | 'SAFE_COUNTED_LATE'
  | 'SAFE_COUNT_BACK_DATED'
  | 'BANK_DEPOSIT_RECORDED'
  | 'DEPOSIT_SLIP_ATTACHED'
  // Day close
  | 'DAY_CLOSED'
  | 'DAY_APPROVED'
  | 'DAY_REOPENED'
  | 'DAY_CLOSE_COMMENTED'
  // Credit collections
  | 'CREDIT_REMINDER_RECORDED'
  | 'CREDIT_PROMISE_RECORDED'
  | 'CREDIT_PROMISE_EDITED'
  | 'CREDIT_PROMISE_CANCELLED'
  | 'COLLECTION_SETTINGS_UPDATED'
  // Fleet vehicles
  | 'CLIENT_VEHICLE_SAVED'
  | 'CLIENT_VEHICLE_STATUS_CHANGED'
  | 'VEHICLE_CHECK_CHANGED'
  | 'STAFF_IMPORTED'
  | 'CREDIT_CLIENTS_IMPORTED'
  | 'PRODUCTS_IMPORTED'
  | 'TANK_STOCK_IMPORTED'
  | 'DEMO_PUMP_CREATED'
  | 'DEMO_PUMP_DELETED'
  // Credit and meter controls
  | 'CREDIT_CLIENT_UPDATED'
  | 'CREDIT_CLIENT_STATUS_CHANGED'
  | 'CREDIT_PORTAL_LOCKED'
  | 'CREDIT_CLOSE_BLOCKED_ALLOWED'
  | 'METER_READING_ADJUSTED'
  | 'METER_ADJUSTMENT_REQUESTED'
  | 'METER_ADJUSTMENT_APPROVED'
  | 'METER_ADJUSTMENT_REJECTED'
  | 'CREDIT_NOTE_ISSUED'
  | 'CREDIT_ADVANCE_REFUNDED'
  | 'CREDIT_DEPOSIT_RECEIVED'
  | 'CREDIT_DEPOSIT_APPLIED'
  | 'CREDIT_DEPOSIT_REFUNDED'
  | 'CREDIT_WRITTEN_OFF'
  | 'CREDIT_ACCOUNT_CLOSED'
  | 'CREDIT_ACCOUNT_REOPENED'
  | 'CREDIT_CLIENTS_MERGED'
  | 'CREDIT_DISPUTE_RAISED'
  | 'CREDIT_DISPUTE_RESOLVED'

export interface AuditLog {
  id: number
  pumpId: number
  action: AuditAction
  entityType: string
  entityId: string | null
  description: string
  actorId: number | null
  actorName: string
  createdAt: string
}

// Spring Boot 3.3+ wraps pagination metadata under a nested "page" object
export interface AuditPage {
  content: AuditLog[]
  page: {
    size: number
    number: number       // zero-based current page
    totalElements: number
    totalPages: number
  }
}

export interface AuditLogsParams {
  pumpId: number
  page: number
  size: number
  from?: string        // yyyy-MM-dd
  to?: string          // yyyy-MM-dd
  action?: AuditAction
  actorId?: number
  /** Leaves out "Signed in" events. */
  hideSignIns?: boolean
}

export interface AuditActor {
  actorId: number
  actorName: string
}

export const auditApi = {
  getAuditLogs: ({ pumpId, page, size, from, to, action, actorId, hideSignIns }: AuditLogsParams): Promise<AuditPage> =>
    client.get(`/pumps/${pumpId}/audit-logs`, {
      params: {
        page, size, sort: 'createdAt,desc',
        ...(from && { from }), ...(to && { to }), ...(action && { action }), ...(actorId && { actorId }),
        ...(hideSignIns && { hideSignIns: true }),
      },
    }).then(r => r.data),

  /** Everyone with an audit event at the pump, for the "by user" filter. */
  getActors: (pumpId: number): Promise<AuditActor[]> =>
    client.get(`/pumps/${pumpId}/audit-logs/actors`).then(r => r.data),
}
