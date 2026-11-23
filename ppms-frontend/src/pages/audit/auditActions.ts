import type { AuditAction } from '../../api/auditApi'
import type { StatusTone } from '../../components/StatusBadge'

export const AUDIT_ACTION_LABELS: Record<AuditAction, string> = {
  // User management
  USER_CREATED:                       'Staff added',
  USER_UPDATED:                       'Staff updated',
  USER_DEACTIVATED:                   'Staff deactivated',
  USER_STATUS_CHANGED:                'Staff status changed',
  PASSWORD_RESET:                     'Password reset',
  USER_PUMP_CHANGED:                  'Staff moved to another pump',
  // Authentication
  LOGIN:                              'Signed in',
  LOGIN_FAILED:                       'Sign-in failed',
  TOKEN_REVOKED:                      'Session ended',
  // Fuel pricing
  FUEL_PRICE_UPDATED:                 'Fuel price updated',
  // Shift lifecycle
  SHIFT_OPENED:                       'Shift opened',
  SHIFT_CLOSED:                       'Shift closed',
  SHIFT_BACKFILLED:                   'Past shift entered',
  DISCREPANCY_RESOLVED:               'Discrepancy resolved',
  DISCREPANCY_INVESTIGATION_STARTED:  'Discrepancy investigation started',
  // Credit management
  CREDIT_ENTRY_VOIDED:                'Credit entry voided',
  CREDIT_LIMIT_CHANGED:               'Credit limit changed',
  CREDIT_PAYMENT_RECEIVED:            'Credit payment received',
  INTEREST_APPLIED:                   'Interest applied',
  INTEREST_CHARGE_WAIVED:             'Interest waived',
  INTEREST_SETTINGS_CHANGED:          'Interest settings changed',
  CREDIT_CLIENT_CREATED:              'Credit customer added',
  CREDIT_CLIENT_DELETED:              'Credit customer deleted',
  CREDIT_ENTRY_REASSIGNED:            'Credit entry reassigned',
  CREDIT_INVOICE_ISSUED:              'Tax invoice issued',
  CREDIT_INVOICE_CANCELLED:           'Tax invoice cancelled',
  CREDIT_EXTENSION_GRANTED:           'Credit extension granted',
  CREDIT_EXTENSION_REVOKED:           'Credit extension revoked',
  INTEREST_RECALCULATED:              'Interest recalculated',
  // Inventory
  DELIVERY_RECORDED:                  'Fuel delivery recorded',
  DELIVERY_UPDATED:                   'Fuel delivery updated',
  DELIVERY_DELETED:                   'Fuel delivery deleted',
  LOT_COST_CORRECTED:                 'Fuel cost corrected',
  DIP_CHECK_RECORDED:                 'Dip check recorded',
  // Documents
  DOCUMENT_ADDED:                     'Document added',
  // Expenses
  EXPENSE_RECORDED:                   'Expense recorded',
  EXPENSE_APPROVED:                   'Expense approved',
  EXPENSE_REJECTED:                   'Expense rejected',
  // Payroll
  PAYROLL_APPROVED:                   'Payroll approved',
  PAYROLL_DELETED:                    'Payroll deleted',
  // Pump management
  PUMP_CREATED:                       'Pump created',
  PUMP_DELETED:                       'Pump deleted',
  PUMP_UPDATED:                       'Pump updated',
  PUMP_SUSPENDED:                     'Pump suspended',
  PUMP_ENABLED:                       'Pump enabled',
  OWNER_ONBOARDED:                    'Owner onboarded',
  NOZZLE_ADDED:                       'Nozzle added',
  CALIBRATION_LOGGED:                 'Nozzle calibration logged',
  TANK_ADDED:                         'Tank added',
  // Ancillary products
  ANCILLARY_SALE_RECORDED:            'Product sale recorded',
  ANCILLARY_PRICE_OVERRIDDEN:         'Product price overridden',
  ANCILLARY_SALE_BACKFILLED:          'Past product sale entered',
  ANCILLARY_DELIVERY_RECORDED:        'Product delivery recorded',
  ANCILLARY_DELIVERY_BACKFILLED:      'Past product delivery entered',
  // Operations
  HANDOVER_COMPLETED:                 'Shift handed over',
  PUMP_CLOSURE_ADDED:                 'Pump closure added',
  BANK_STATEMENT_IMPORTED:            'Bank statement imported',
  SUPPLIER_PAYMENT_RECORDED:          'Supplier payment recorded',
  PUMP_CONFIG_UPDATED:                'Pump settings updated',
  // Payment settlements
  SETTLEMENT_RECORDED:                'Settlement recorded',
  SETTLEMENT_VOIDED:                  'Settlement voided',
  SETTLEMENT_CONFIG_UPDATED:          'Settlement alerts updated',
}

export const AUDIT_ACTION_TONES: Record<AuditAction, StatusTone> = {
  // User management
  USER_CREATED:                       'info',
  USER_UPDATED:                       'neutral',
  USER_DEACTIVATED:                   'bad',
  USER_STATUS_CHANGED:                'warn',
  PASSWORD_RESET:                     'warn',
  USER_PUMP_CHANGED:                  'info',
  // Authentication
  LOGIN:                              'neutral',
  LOGIN_FAILED:                       'bad',
  TOKEN_REVOKED:                      'neutral',
  // Fuel pricing
  FUEL_PRICE_UPDATED:                 'neutral',
  // Shift lifecycle
  SHIFT_OPENED:                       'info',
  SHIFT_CLOSED:                       'ok',
  SHIFT_BACKFILLED:                   'info',
  DISCREPANCY_RESOLVED:               'warn',
  DISCREPANCY_INVESTIGATION_STARTED:  'warn',
  // Credit management
  CREDIT_ENTRY_VOIDED:                'bad',
  CREDIT_LIMIT_CHANGED:               'warn',
  CREDIT_PAYMENT_RECEIVED:            'ok',
  INTEREST_APPLIED:                   'warn',
  INTEREST_CHARGE_WAIVED:             'warn',
  INTEREST_SETTINGS_CHANGED:          'neutral',
  CREDIT_CLIENT_CREATED:              'info',
  CREDIT_CLIENT_DELETED:              'bad',
  CREDIT_ENTRY_REASSIGNED:            'warn',
  CREDIT_INVOICE_ISSUED:              'ok',
  CREDIT_INVOICE_CANCELLED:           'bad',
  CREDIT_EXTENSION_GRANTED:           'ok',
  CREDIT_EXTENSION_REVOKED:           'bad',
  INTEREST_RECALCULATED:              'warn',
  // Inventory
  DELIVERY_RECORDED:                  'info',
  DELIVERY_UPDATED:                   'warn',
  DELIVERY_DELETED:                   'bad',
  LOT_COST_CORRECTED:                 'warn',
  DIP_CHECK_RECORDED:                 'info',
  // Documents
  DOCUMENT_ADDED:                     'neutral',
  // Expenses
  EXPENSE_RECORDED:                   'neutral',
  EXPENSE_APPROVED:                   'ok',
  EXPENSE_REJECTED:                   'bad',
  // Payroll
  PAYROLL_APPROVED:                   'ok',
  PAYROLL_DELETED:                    'bad',
  // Pump management
  PUMP_CREATED:                       'info',
  PUMP_DELETED:                       'bad',
  PUMP_UPDATED:                       'neutral',
  PUMP_SUSPENDED:                     'bad',
  PUMP_ENABLED:                       'ok',
  OWNER_ONBOARDED:                    'info',
  NOZZLE_ADDED:                       'info',
  CALIBRATION_LOGGED:                 'info',
  TANK_ADDED:                         'info',
  // Ancillary products
  ANCILLARY_SALE_RECORDED:            'ok',
  ANCILLARY_PRICE_OVERRIDDEN:         'warn',
  ANCILLARY_SALE_BACKFILLED:          'ok',
  ANCILLARY_DELIVERY_RECORDED:        'info',
  ANCILLARY_DELIVERY_BACKFILLED:      'info',
  // Operations
  HANDOVER_COMPLETED:                 'ok',
  PUMP_CLOSURE_ADDED:                 'neutral',
  BANK_STATEMENT_IMPORTED:            'info',
  SUPPLIER_PAYMENT_RECORDED:          'ok',
  PUMP_CONFIG_UPDATED:                'neutral',
  // Payment settlements
  SETTLEMENT_RECORDED:                'ok',
  SETTLEMENT_VOIDED:                  'bad',
  SETTLEMENT_CONFIG_UPDATED:          'neutral',
}
