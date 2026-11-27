import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expenseApi } from '../../api/expenseApi'
import type { PumpExpense } from '../../api/expenseApi'
import { useAuthStore } from '../../store/authStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { ExpenseList } from './ExpenseList'
import type { ExpenseListState } from './useExpenseList'

vi.mock('../../api/expenseApi', () => ({ expenseApi: { approveExpense: vi.fn(), voidExpense: vi.fn() } }))

const pending = {
  id: 7, pumpId: 3, category: 'MAINTENANCE', amount: 1200, description: 'Compressor repair', expenseDate: '2026-11-20',
  recordedByUserId: 9, approvalStatus: 'PENDING_APPROVAL', approvedByUserId: null, approvedAt: null, approvalNotes: null,
  submittedByUserId: 9, submittedAt: '2026-11-20T10:00:00Z', createdAt: '2026-11-20T10:00:00Z',
} as PumpExpense

const list = {
  expenses: [pending], expensesPage: undefined, isLoading: false,
  categoryFilter: '', setCategoryFilter: vi.fn(), statusFilter: '', setStatusFilter: vi.fn(),
  setPage: vi.fn(), setPageSize: vi.fn(),
} as unknown as ExpenseListState

describe('ExpenseList rejection', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    vi.mocked(expenseApi.approveExpense).mockReset().mockResolvedValue({ ...pending, approvalStatus: 'REJECTED' })
  })

  it('asks for a reason before rejecting and sends it as the rejection note', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExpenseList pumpId={3} list={list} />)

    await user.click(screen.getByRole('button', { name: 'Reject' }))
    const dialog = screen.getByRole('dialog', { name: 'Reject Expense' })
    expect(expenseApi.approveExpense).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Enter a reason for rejecting.')
    expect(expenseApi.approveExpense).not.toHaveBeenCalled()

    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Duplicate bill')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))

    expect(expenseApi.approveExpense).toHaveBeenCalledWith(3, 7, { action: 'REJECTED', notes: 'Duplicate bill' })
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('still approves with one click', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ExpenseList pumpId={3} list={list} />)

    await user.click(screen.getByRole('button', { name: 'Approve' }))

    expect(expenseApi.approveExpense).toHaveBeenCalledWith(3, 7, { action: 'APPROVED' })
  })
})

const approvedFromSafe = {
  ...pending, id: 8, approvalStatus: 'APPROVED', paidFrom: 'SAFE', cashEventId: 44, description: 'Hose repair',
} as PumpExpense

describe('ExpenseList void', () => {
  const approvedList = { ...list, expenses: [approvedFromSafe] } as unknown as ExpenseListState

  beforeEach(() => {
    vi.mocked(expenseApi.voidExpense).mockReset().mockResolvedValue({ ...approvedFromSafe, approvalStatus: 'VOIDED' })
  })

  it('shows where an expense was paid from and lets an admin void it with a reason', async () => {
    useAuthStore.setState({ user: { userId: 2, fullName: 'Admin', role: 'ADMIN', assignedPumpId: 3 }, isAuthenticated: true })
    const user = userEvent.setup()
    renderWithProviders(<ExpenseList pumpId={3} list={approvedList} />)

    expect(screen.getByText(/Paid from Safe/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Void' }))
    const dialog = screen.getByRole('dialog', { name: 'Void expense' })
    expect(within(dialog).getByText('The cash it took from Safe goes back. Record it again if it was entered wrong.')).toBeInTheDocument()

    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Entered twice')
    await user.click(within(dialog).getByRole('button', { name: 'Void' }))

    expect(expenseApi.voidExpense).toHaveBeenCalledWith(3, 8, 'Entered twice')
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('offers no void to a manager', () => {
    useAuthStore.setState({ user: { userId: 9, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    renderWithProviders(<ExpenseList pumpId={3} list={approvedList} />)

    expect(screen.queryByRole('button', { name: 'Void' })).not.toBeInTheDocument()
  })
})
