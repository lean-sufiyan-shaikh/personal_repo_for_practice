import type { AuditAction } from '../../api/auditApi'

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

export const AUDIT_ACTION_STYLES: Record<AuditAction, string> = {
  // User management
  USER_CREATED:                       'bg-blue-100 text-blue-700',
  USER_UPDATED:                       'bg-purple-100 text-purple-700',
  USER_DEACTIVATED:                   'bg-red-100 text-red-700',
  USER_STATUS_CHANGED:                'bg-amber-100 text-amber-700',
  PASSWORD_RESET:                     'bg-amber-100 text-amber-700',
  USER_PUMP_CHANGED:                  'bg-sky-100 text-sky-700',
  // Authentication
  LOGIN:                              'bg-slate-100 text-slate-600',
  LOGIN_FAILED:                       'bg-red-100 text-red-700',
  TOKEN_REVOKED:                      'bg-slate-100 text-slate-600',
  // Fuel pricing
  FUEL_PRICE_UPDATED:                 'bg-purple-100 text-purple-700',
  // Shift lifecycle
  SHIFT_OPENED:                       'bg-sky-100 text-sky-700',
  SHIFT_CLOSED:                       'bg-emerald-100 text-emerald-700',
  SHIFT_BACKFILLED:                   'bg-sky-100 text-sky-700',
  DISCREPANCY_RESOLVED:               'bg-amber-100 text-amber-700',
  DISCREPANCY_INVESTIGATION_STARTED:  'bg-amber-100 text-amber-700',
  // Credit management
  CREDIT_ENTRY_VOIDED:                'bg-red-100 text-red-700',
  CREDIT_LIMIT_CHANGED:               'bg-orange-100 text-orange-700',
  CREDIT_PAYMENT_RECEIVED:            'bg-teal-100 text-teal-700',
  INTEREST_APPLIED:                   'bg-orange-100 text-orange-700',
  INTEREST_CHARGE_WAIVED:             'bg-amber-100 text-amber-700',
  INTEREST_SETTINGS_CHANGED:          'bg-purple-100 text-purple-700',
  CREDIT_CLIENT_CREATED:              'bg-blue-100 text-blue-700',
  CREDIT_CLIENT_DELETED:              'bg-red-100 text-red-700',
  CREDIT_ENTRY_REASSIGNED:            'bg-amber-100 text-amber-700',
  CREDIT_INVOICE_ISSUED:              'bg-teal-100 text-teal-700',
  CREDIT_INVOICE_CANCELLED:           'bg-red-100 text-red-700',
  CREDIT_EXTENSION_GRANTED:           'bg-emerald-100 text-emerald-700',
  CREDIT_EXTENSION_REVOKED:           'bg-red-100 text-red-700',
  INTEREST_RECALCULATED:              'bg-orange-100 text-orange-700',
  // Inventory
  DELIVERY_RECORDED:                  'bg-indigo-100 text-indigo-700',
  DELIVERY_UPDATED:                   'bg-amber-100 text-amber-700',
  DELIVERY_DELETED:                   'bg-red-100 text-red-700',
  LOT_COST_CORRECTED:                 'bg-amber-100 text-amber-700',
  DIP_CHECK_RECORDED:                 'bg-indigo-100 text-indigo-700',
  // Documents
  DOCUMENT_ADDED:                     'bg-slate-100 text-slate-700',
  // Expenses
  EXPENSE_RECORDED:                   'bg-rose-100 text-rose-700',
  EXPENSE_APPROVED:                   'bg-ok-bg text-ok',
  EXPENSE_REJECTED:                   'bg-bad-bg text-bad',
  // Payroll
  PAYROLL_APPROVED:                   'bg-emerald-100 text-emerald-700',
  PAYROLL_DELETED:                    'bg-red-100 text-red-700',
  // Pump management
  PUMP_CREATED:                       'bg-blue-100 text-blue-700',
  PUMP_DELETED:                       'bg-red-100 text-red-700',
  PUMP_UPDATED:                       'bg-purple-100 text-purple-700',
  PUMP_SUSPENDED:                     'bg-red-100 text-red-700',
  PUMP_ENABLED:                       'bg-emerald-100 text-emerald-700',
  OWNER_ONBOARDED:                    'bg-blue-100 text-blue-700',
  NOZZLE_ADDED:                       'bg-blue-100 text-blue-700',
  CALIBRATION_LOGGED:                 'bg-indigo-100 text-indigo-700',
  TANK_ADDED:                         'bg-blue-100 text-blue-700',
  // Ancillary products
  ANCILLARY_SALE_RECORDED:            'bg-teal-100 text-teal-700',
  ANCILLARY_PRICE_OVERRIDDEN:         'bg-amber-100 text-amber-700',
  ANCILLARY_SALE_BACKFILLED:          'bg-teal-100 text-teal-700',
  ANCILLARY_DELIVERY_RECORDED:        'bg-indigo-100 text-indigo-700',
  ANCILLARY_DELIVERY_BACKFILLED:      'bg-indigo-100 text-indigo-700',
  // Operations
  HANDOVER_COMPLETED:                 'bg-emerald-100 text-emerald-700',
  PUMP_CLOSURE_ADDED:                 'bg-slate-100 text-slate-700',
  BANK_STATEMENT_IMPORTED:            'bg-indigo-100 text-indigo-700',
  SUPPLIER_PAYMENT_RECORDED:          'bg-teal-100 text-teal-700',
  PUMP_CONFIG_UPDATED:                'bg-purple-100 text-purple-700',
  // Payment settlements
  SETTLEMENT_RECORDED:                'bg-teal-100 text-teal-700',
  SETTLEMENT_VOIDED:                  'bg-red-100 text-red-700',
  SETTLEMENT_CONFIG_UPDATED:          'bg-purple-100 text-purple-700',
}
