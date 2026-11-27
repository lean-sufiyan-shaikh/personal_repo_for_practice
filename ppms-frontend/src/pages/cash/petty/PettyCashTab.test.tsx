import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pettyCashApi } from '../../../api/pettyCashApi'
import type { PettyCashSummary } from '../../../api/pettyCashApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import { PettyCashTab } from './PettyCashTab'

vi.mock('../../../api/pettyCashApi', () => ({
  pettyCashApi: { getSummary: vi.fn(), getLedger: vi.fn(), topUp: vi.fn(), recordCount: vi.fn(), updateSettings: vi.fn() },
}))

const summary: PettyCashSummary = {
  floatAmount: 2000, countTolerance: 10, balance: 1250, suggestedTopUp: 750,
  latestCount: {
    id: 3, countForDate: '2026-11-26', countedAmount: 1200, expectedAmount: 1250, varianceAmount: -50, toleranceAmount: 10,
    beyondTolerance: true, notes: null, countedByName: 'Meena', countedAt: '2026-11-26T10:00:00Z',
  },
}

const ledger = {
  content: [{
    id: 5, entryType: 'EXPENSE' as const, amount: -120, balanceAfter: 1250, description: 'Expense #12 (OTHER): Tea',
    entryDate: '2026-11-26', expenseId: 12, reversed: false, createdAt: '2026-11-26T09:00:00Z',
  }],
  page: 0, pageSize: 10, totalElements: 1, totalPages: 1, hasNext: false, hasPrevious: false,
}

describe('PettyCashTab', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.mocked(pettyCashApi.getSummary).mockResolvedValue(summary)
    vi.mocked(pettyCashApi.getLedger).mockResolvedValue(ledger)
    vi.mocked(pettyCashApi.topUp).mockReset().mockResolvedValue(ledger.content[0])
    vi.mocked(pettyCashApi.recordCount).mockReset().mockResolvedValue(summary.latestCount!)
  })

  it('shows the balance, the top-up back to the float, the short last count and the ledger', async () => {
    renderWithProviders(<PettyCashTab pumpId={3} canManage canSetFloat={false} />)

    expect(await screen.findByText('Top up ₹750.00 to bring it back to the float')).toBeInTheDocument()
    expect(screen.getByText('Short ₹50.00')).toBeInTheDocument()
    expect(await screen.findByText('Expense #12 (OTHER): Tea')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Float and tolerance' })).not.toBeInTheDocument()
  })

  it('tops up from the safe starting at the suggested amount and sends one key for one request', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PettyCashTab pumpId={3} canManage canSetFloat />)

    await user.click(await screen.findByRole('button', { name: 'Top up from safe' }))
    const dialog = screen.getByRole('dialog', { name: 'Top up petty cash' })
    expect(within(dialog).getByLabelText('Amount')).toHaveValue(750)
    await user.click(within(dialog).getByRole('button', { name: 'Top up from safe' }))

    expect(pettyCashApi.topUp).toHaveBeenCalledWith(3, { amount: 750, entryDate: undefined, notes: undefined }, expect.any(String))
  })

  it('opens the count for the day the Day Close checklist links to, full screen on a phone', async () => {
    setScreenWidth(390)
    const user = userEvent.setup()
    renderWithProviders(<PettyCashTab pumpId={3} canManage canSetFloat={false} />,
      { route: '/dashboard/cash?tab=petty&action=count-petty&countFor=2026-11-20' })

    const dialog = await screen.findByRole('dialog', { name: 'Count petty cash' })
    expect(within(dialog).getByLabelText('Count for')).toHaveValue('2026-11-20')
    await user.type(within(dialog).getByLabelText('Cash counted'), '900')
    await user.click(within(dialog).getByRole('button', { name: /Save/ }))

    expect(pettyCashApi.recordCount).toHaveBeenCalledWith(3,
      { countedAmount: 900, countForDate: '2026-11-20', notes: undefined }, expect.any(String))
  })
})
