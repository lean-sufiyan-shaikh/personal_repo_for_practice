import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { PayrollRecord, PayrollStatus } from '../../api/payrollApi'
import { PayrollGroup } from './PayrollGroup'

function record(status: PayrollStatus): PayrollRecord {
  return {
    id: 7, pumpId: 1, userId: 3, periodFrom: '2026-11-01', periodTo: '2026-11-30', salaryType: 'DAILY',
    totalShifts: 0, shift1Shifts: 0, shift1Hours: 0, shift1RateSnapshot: null, standardShifts: 0, standardHours: 0, standardRateSnapshot: null,
    totalDays: 30, leaveDays: 0, daysWorked: 30, dailyRateSnapshot: 500,
    grossAmount: 15_000, deductions: 0, netPay: 15_000, uncoveredDeductions: 0, openingDeductions: 0,
    carriedToPayrollId: null, notes: null, status, approvedBy: null,
    paidDate: null, paymentMode: null, paymentReference: null, paidBy: null, cashEventId: null,
    createdAt: '2026-11-30T00:00:00Z', updatedAt: '2026-11-30T00:00:00Z',
  }
}

function renderGroup(status: PayrollStatus, isOpen = true, shown: PayrollRecord = record(status)) {
  const onStatusChange = vi.fn()
  const onMarkPaid = vi.fn()
  const onDelete = vi.fn()
  render(
    <PayrollGroup
      status={status} records={[shown]} totalRecords={1} totalAmount={15_000}
      page={0} pageSize={10} onPageChange={vi.fn()} onPageSizeChange={vi.fn()}
      isOpen={isOpen} onToggle={vi.fn()} staff={[]}
      onStatusChange={onStatusChange} onMarkPaid={onMarkPaid} onDelete={onDelete} isPending={false} isDeletePending={false}
    />,
  )
  return { onStatusChange, onMarkPaid, onDelete }
}

describe('PayrollGroup', () => {
  it('opens the payment details for an approved record instead of marking it paid at once', async () => {
    const user = userEvent.setup()
    const { onStatusChange, onMarkPaid } = renderGroup('APPROVED')

    await user.click(screen.getByRole('button', { name: 'Mark as paid' }))

    expect(onStatusChange).not.toHaveBeenCalled()
    expect(onMarkPaid).toHaveBeenCalledWith(expect.objectContaining({ id: 7 }))
  })

  it('shows how a paid record was paid, the deductions carried in and those carried on', () => {
    renderGroup('PAID', true, {
      ...record('PAID'), grossAmount: 1000, deductions: 1300, openingDeductions: 700, netPay: 0, uncoveredDeductions: 300,
      carriedToPayrollId: 9, paidDate: '2026-11-30', paymentMode: 'UPI', paymentReference: 'UTR 55',
    })

    expect(screen.getByText('Salary deductions').closest('div')?.parentElement).toHaveTextContent('−₹600.00')
    expect(screen.getByText('Carried from earlier payroll').closest('div')?.parentElement).toHaveTextContent('−₹700.00')
    expect(screen.getByText('₹300.00 of deductions was carried into the next payroll.')).toBeInTheDocument()
    expect(screen.getByText(/Paid on 30 Nov 2026 by UPI \(UTR 55\)/)).toBeInTheDocument()
  })

  it('reports whether the group is open and points the header at its records', () => {
    renderGroup('DRAFT')
    const header = screen.getByRole('button', { expanded: true })
    expect(document.getElementById(header.getAttribute('aria-controls') ?? '')).toHaveTextContent('Mark as approved')
  })

  it('reports a closed group as collapsed with nothing to point at', () => {
    renderGroup('DRAFT', false)
    const header = screen.getByRole('button', { expanded: false })
    expect(header).not.toHaveAttribute('aria-controls')
  })

  it('approves a draft in one click', async () => {
    const user = userEvent.setup()
    const { onStatusChange } = renderGroup('DRAFT')

    await user.click(screen.getByRole('button', { name: 'Mark as approved' }))
    expect(onStatusChange).toHaveBeenCalledWith(7, 'APPROVED')
  })

  it('calls removing a draft Delete, not Reject, and deletes only after confirmation', async () => {
    const user = userEvent.setup()
    const { onDelete } = renderGroup('DRAFT')
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.getByText('Delete this draft? The record is removed for good.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'No' }))
    expect(onDelete).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await user.click(screen.getByRole('button', { name: 'Yes' }))
    expect(onDelete).toHaveBeenCalledWith(7)
  })
})
