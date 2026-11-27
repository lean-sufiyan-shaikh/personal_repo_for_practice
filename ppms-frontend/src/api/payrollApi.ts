import client from './client'

export type PayrollStatus = 'DRAFT' | 'APPROVED' | 'PAID'

/** A CASH payment is taken out of the safe on the paid date. */
export type PayrollPaymentMode = 'CASH' | 'BANK' | 'UPI'

export interface PayrollRecord {
  id: number
  pumpId: number
  userId: number
  periodFrom: string
  periodTo: string
  salaryType: 'HOURLY_SHIFT' | 'DAILY'
  // HOURLY_SHIFT fields (operators)
  totalShifts: number
  shift1Shifts: number
  shift1Hours: number
  shift1RateSnapshot: number | null
  standardShifts: number
  standardHours: number
  standardRateSnapshot: number | null
  // DAILY fields (managers / admins / accountants)
  totalDays: number | null
  leaveDays: number | null
  daysWorked: number | null
  dailyRateSnapshot: number | null
  grossAmount: number
  /** Shift deductions plus openingDeductions. */
  deductions: number
  netPay: number
  /** Deductions above the gross pay; carried into the next payroll of the staff member. */
  uncoveredDeductions: number
  /** Deductions earlier payroll could not take, brought into this one. */
  openingDeductions: number
  /** The payroll this record's uncovered deductions were carried into. */
  carriedToPayrollId: number | null
  notes: string | null
  status: PayrollStatus
  approvedBy: number | null
  paidDate: string | null
  /** Null on payroll marked paid before the payment mode was recorded. */
  paymentMode: PayrollPaymentMode | null
  paymentReference: string | null
  paidBy: number | null
  cashEventId: number | null
  createdAt: string
  updatedAt: string
}

export interface PendingDiscrepancy {
  id: number
  shiftDate: string
  duNumber: number | null
  duName: string | null
  discrepancyType: 'SHORT' | 'OVER'
  discrepancyAmount: number
  discrepancyReason: string | null
}

export interface GeneratePayrollRequest {
  userId: number
  periodFrom: string
  periodTo: string
  notes: string | null
  /** Shift IDs the owner chose to resolve as SALARY_DEDUCTION in this payroll run. */
  deductFromSalaryShiftIds?: number[]
}

export interface PayrollPayment {
  paidDate: string
  paymentMode: PayrollPaymentMode
  paymentReference?: string
}

export const payrollApi = {
  getPayroll: (pumpId: number): Promise<PayrollRecord[]> =>
    client.get(`/pumps/${pumpId}/payroll`).then(r => r.data),

  getPendingDiscrepancies: (pumpId: number, userId: number): Promise<PendingDiscrepancy[]> =>
    client.get(`/pumps/${pumpId}/payroll/pending-discrepancies`, { params: { userId } }).then(r => r.data),

  generatePayroll: (pumpId: number, data: GeneratePayrollRequest): Promise<PayrollRecord> =>
    client.post(`/pumps/${pumpId}/payroll/generate`, data).then(r => r.data),

  deletePayroll: (pumpId: number, recordId: number): Promise<void> =>
    client.delete(`/pumps/${pumpId}/payroll/${recordId}`).then(() => undefined),

  updateStatus: (pumpId: number, recordId: number, status: PayrollStatus): Promise<PayrollRecord> =>
    client.patch(`/pumps/${pumpId}/payroll/${recordId}/status`, { status }).then(r => r.data),

  markPaid: (pumpId: number, recordId: number, payment: PayrollPayment): Promise<PayrollRecord> =>
    client.patch(`/pumps/${pumpId}/payroll/${recordId}/status`, { status: 'PAID', ...payment }).then(r => r.data),
}
