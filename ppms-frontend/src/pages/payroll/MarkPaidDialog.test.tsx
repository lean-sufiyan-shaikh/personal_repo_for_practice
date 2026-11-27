import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { payrollApi } from '../../api/payrollApi'
import type { PayrollRecord } from '../../api/payrollApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { addDays, istDateString } from '../../utils/date'
import { MarkPaidDialog } from './MarkPaidDialog'

vi.mock('../../api/payrollApi', () => ({
  payrollApi: { markPaid: vi.fn() },
}))

const approved: PayrollRecord = {
  id: 7, pumpId: 1, userId: 3, periodFrom: '2026-11-01', periodTo: '2026-11-30', salaryType: 'DAILY',
  totalShifts: 0, shift1Shifts: 0, shift1Hours: 0, shift1RateSnapshot: null, standardShifts: 0, standardHours: 0,
  standardRateSnapshot: null, totalDays: 30, leaveDays: 0, daysWorked: 30, dailyRateSnapshot: 500,
  grossAmount: 15_000, deductions: 0, netPay: 15_000, uncoveredDeductions: 0, openingDeductions: 0, carriedToPayrollId: null,
  notes: null, status: 'APPROVED', approvedBy: 1, paidDate: null, paymentMode: null, paymentReference: null, paidBy: null,
  cashEventId: null, createdAt: '2026-11-30T00:00:00Z', updatedAt: '2026-11-30T00:00:00Z',
}

describe('MarkPaidDialog', () => {
  beforeEach(() => {
    vi.mocked(payrollApi.markPaid).mockReset().mockResolvedValue({ ...approved, status: 'PAID' })
  })

  it('records the paid date, mode and reference, and says cash comes out of the safe', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(<MarkPaidDialog pumpId={1} record={approved} staffName="Ravi" onClose={onClose} />)

    expect(screen.getByText('Net pay').nextSibling).toHaveTextContent('₹15,000.00')
    expect(screen.getByLabelText('Paid on')).toHaveValue(istDateString())
    expect(screen.queryByText(/taken out of the safe/)).not.toBeInTheDocument()
    await user.click(screen.getByLabelText('Cash'))
    expect(screen.getByText('Cash is taken out of the safe on the paid date.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/^Reference/), ' Voucher 12 ')
    await user.click(screen.getByRole('button', { name: 'Mark as paid' }))

    expect(payrollApi.markPaid).toHaveBeenCalledWith(1, 7,
      { paidDate: istDateString(), paymentMode: 'CASH', paymentReference: 'Voucher 12' })
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses a paid date in the future before calling the server', async () => {
    const user = userEvent.setup()
    renderWithProviders(<MarkPaidDialog pumpId={1} record={approved} staffName="Ravi" onClose={vi.fn()} />)

    await user.clear(screen.getByLabelText('Paid on'))
    await user.type(screen.getByLabelText('Paid on'), addDays(istDateString(), 1))
    await user.click(screen.getByRole('button', { name: 'Mark as paid' }))

    expect(screen.getByLabelText('Paid on')).toBeInvalid()
    expect(payrollApi.markPaid).not.toHaveBeenCalled()
  })
})
