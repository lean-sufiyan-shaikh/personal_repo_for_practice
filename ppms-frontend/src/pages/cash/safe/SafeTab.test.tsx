import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cashApi } from '../../../api/cashApi'
import type { CashDrop, SafeSummary } from '../../../api/cashApi'
import { useAuthStore } from '../../../store/authStore'
import { usePumpStore } from '../../../store/usePumpStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import CashDrawerPage from '../CashDrawerPage'

vi.mock('../../../api/cashApi', () => ({
  cashApi: {
    getCashEvents: vi.fn(), recordCashEvent: vi.fn(), getSafe: vi.fn(), getPendingDrops: vi.fn(), confirmDrop: vi.fn(),
    rejectDrop: vi.fn(), getSafeLedger: vi.fn(), getDeposits: vi.fn(), recordSafeCount: vi.fn(), recordDeposit: vi.fn(),
    attachDepositSlip: vi.fn(), getDepositSlip: vi.fn(),
  },
}))

const emptyPage = { content: [], page: 0, pageSize: 10, totalElements: 0, totalPages: 0, hasNext: false, hasPrevious: false }

const summary: SafeSummary = {
  balance: 5000,
  pendingDropCount: 1,
  pendingDropAmount: 600,
  countTolerance: 10,
  latestCount: {
    id: 4, countedAmount: 4980, expectedAmount: 5000, varianceAmount: -20, varianceType: 'SHORT', toleranceAmount: 10,
    beyondTolerance: true, denominations: null, notes: null, countedByName: 'Meena', countedAt: '2026-11-26T10:00:00Z',
  },
}

const drop: CashDrop = {
  id: 9, shiftId: 21, operatorId: 5, operatorName: 'Ravi', amount: 600, denominations: null, status: 'PENDING',
  recordedByName: 'Ravi', recordedAt: '2026-11-27T08:30:00Z', reviewedByName: null, reviewedAt: null, rejectionReason: null,
}

function renderSafe(action?: string) {
  renderWithProviders(<CashDrawerPage />, { route: `/dashboard/cash?tab=safe${action ? `&action=${action}` : ''}` })
}

describe('Safe tab', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ user: { userId: 1, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(cashApi.getCashEvents).mockResolvedValue({ currentBalance: 5000, events: emptyPage })
    vi.mocked(cashApi.getSafe).mockResolvedValue(summary)
    vi.mocked(cashApi.getPendingDrops).mockResolvedValue([drop])
    vi.mocked(cashApi.getSafeLedger).mockResolvedValue({
      ...emptyPage, totalElements: 2, totalPages: 1,
      content: [
        { id: 2, eventType: 'BANK_DEPOSIT', amount: -800, balanceAfter: 5000, description: 'Bank deposit to SBI (slip S-1)', eventDate: '2026-11-27', createdAt: '2026-11-27T09:00:00Z' },
        { id: 1, eventType: 'CASH_DROP', amount: 600, balanceAfter: 5800, description: 'Cash drop from shift #21', eventDate: '2026-11-27', createdAt: '2026-11-27T08:40:00Z' },
      ],
    })
    vi.mocked(cashApi.getDeposits).mockResolvedValue({
      totalCount: 1, totalAmount: 800,
      deposits: { ...emptyPage, totalElements: 1, totalPages: 1, content: [{
        id: 7, amount: 800, bankAccount: 'SBI ••1234', depositDate: '2026-11-27', slipReference: 'S-1', notes: null,
        hasSlipPhoto: false, recordedByName: 'Meena', createdAt: '2026-11-27T09:00:00Z',
      }] },
    })
  })

  it('shows the safe balance and the last count with its shortage beyond tolerance', async () => {
    renderSafe()
    expect(await screen.findByText('₹5,000.00', { selector: '.text-3xl' })).toBeInTheDocument()
    expect(screen.getByText('Short ₹20.00')).toBeInTheDocument()
    expect(screen.getByText('Counted ₹4,980.00 against ₹5,000.00 expected')).toBeInTheDocument()
    expect(screen.getByText(/Beyond the ₹10.00 tolerance/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Safe' })).toHaveAttribute('aria-current', 'page')
  })

  it('confirms a waiting drop', async () => {
    const user = userEvent.setup()
    vi.mocked(cashApi.confirmDrop).mockResolvedValue({ ...drop, status: 'CONFIRMED' })
    renderSafe()
    const pending = await screen.findByRole('region', { name: 'Drops waiting for confirmation' })
    expect(within(pending).getByText('1 drop · ₹600.00')).toBeInTheDocument()

    await user.click(within(pending).getByRole('button', { name: 'Confirm' }))
    expect(cashApi.confirmDrop).toHaveBeenCalledWith(3, 9)
  })

  it('asks for a reason before rejecting a drop', async () => {
    const user = userEvent.setup()
    vi.mocked(cashApi.rejectDrop).mockResolvedValue({ ...drop, status: 'REJECTED', rejectionReason: 'Short' })
    renderSafe()
    const pending = await screen.findByRole('region', { name: 'Drops waiting for confirmation' })
    await user.click(within(pending).getByRole('button', { name: 'Reject' }))

    const dialog = await screen.findByRole('dialog', { name: 'Reject cash drop' })
    await user.type(within(dialog).getByRole('textbox'), 'Bag held ₹500')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(cashApi.rejectDrop).toHaveBeenCalledWith(3, 9, 'Bag held ₹500')
  })

  it('lists ledger movements with signed amounts and the balance after each', async () => {
    renderSafe()
    const ledger = await screen.findByRole('region', { name: 'Safe ledger' })
    const rows = await within(ledger).findAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Bank deposit')
    expect(rows[0]).toHaveTextContent('−₹800.00')
    expect(rows[0]).toHaveTextContent('Balance after ₹5,000.00')
    expect(rows[1]).toHaveTextContent('+₹600.00')
  })

  it('shows deposit totals and lets a deposit without a slip get one', async () => {
    const user = userEvent.setup()
    vi.mocked(cashApi.attachDepositSlip).mockResolvedValue({} as Awaited<ReturnType<typeof cashApi.attachDepositSlip>>)
    renderSafe()
    const deposits = await screen.findByRole('region', { name: /Bank deposits/ })
    expect(await within(deposits).findByText('1 deposit · ₹800.00')).toBeInTheDocument()

    const file = new File(['png'], 'slip.png', { type: 'image/png' })
    await user.upload(within(deposits).getByLabelText('Attach slip'), file)
    expect(cashApi.attachDepositSlip).toHaveBeenCalledWith(3, 7, file)
  })

  it('opens the count dialog full screen on a phone', async () => {
    setScreenWidth(390)
    renderSafe('count-safe')
    const dialog = await screen.findByRole('dialog', { name: 'Count safe' })
    expect(document.querySelector('.ui-modal-backdrop')).toHaveClass('ui-modal-backdrop--fullscreen')
    expect(document.querySelector('.ui-modal-backdrop')).not.toHaveClass('ui-modal-backdrop--sheet')
    expect(within(dialog).getByRole('button', { name: 'Add one ₹500 note' })).toBeInTheDocument()
  })
})
