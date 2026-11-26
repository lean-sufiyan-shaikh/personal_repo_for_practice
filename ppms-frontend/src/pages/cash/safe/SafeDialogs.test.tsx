import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cashApi } from '../../../api/cashApi'
import type { BankDeposit, SafeSummary } from '../../../api/cashApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { CountSafeDialog } from './CountSafeDialog'
import { BankDepositDialog } from './BankDepositDialog'

vi.mock('../../../api/cashApi', () => ({
  cashApi: { recordSafeCount: vi.fn(), recordDeposit: vi.fn(), attachDepositSlip: vi.fn() },
}))

const summary: SafeSummary = { balance: 1000, pendingDropCount: 0, pendingDropAmount: 0, countTolerance: 10, latestCount: null }
const deposit = { id: 12, amount: 400 } as BankDeposit

function figure(dialog: HTMLElement, label: string) {
  return within(dialog).getByText(label).nextElementSibling
}

describe('Count safe dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(cashApi.recordSafeCount).mockResolvedValue({} as Awaited<ReturnType<typeof cashApi.recordSafeCount>>)
  })

  it('fills the count from the notes and sends the breakdown', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CountSafeDialog pumpId={3} summary={summary} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Count safe' })

    await user.click(within(dialog).getByRole('button', { name: 'Add one ₹500 note' }))
    await user.type(within(dialog).getByLabelText('Number of ₹200 notes'), '2')
    await user.type(within(dialog).getByLabelText('Coins in rupees'), '5')

    expect(within(dialog).getByText('Total counted').nextElementSibling).toHaveTextContent('₹905.00')
    expect(figure(dialog, 'Difference')).toHaveTextContent('−₹95.00')
    expect(within(dialog).getByText(/beyond the ₹10.00 tolerance/)).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Save count' }))
    expect(cashApi.recordSafeCount).toHaveBeenCalledWith(3, {
      countedAmount: 905,
      denominations: { notes500: 1, notes200: 2, notes100: 0, notes50: 0, notes20: 0, notes10: 0, coins: 5 },
      notes: undefined,
    })
  })

  it('sends only the total when it is typed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CountSafeDialog pumpId={3} summary={summary} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Count safe' })

    await user.click(within(dialog).getByRole('button', { name: 'Just type the total' }))
    await user.type(within(dialog).getByLabelText('Cash in the safe'), '1005')
    expect(figure(dialog, 'Difference')).toHaveTextContent('+₹5.00')
    expect(within(dialog).queryByText(/owner will be notified/)).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Save count' }))
    expect(cashApi.recordSafeCount).toHaveBeenCalledWith(3, { countedAmount: 1005, denominations: undefined, notes: undefined })
  })
})

describe('Bank deposit dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(cashApi.recordDeposit).mockResolvedValue(deposit)
    vi.mocked(cashApi.attachDepositSlip).mockResolvedValue(deposit)
  })

  async function fillDeposit(dialog: HTMLElement, amount: string) {
    const user = userEvent.setup()
    await user.type(within(dialog).getByLabelText('Amount'), amount)
    await user.type(within(dialog).getByLabelText('Bank account'), 'SBI ••1234')
    return user
  }

  it('needs a slip reference and refuses more than the safe holds', async () => {
    renderWithProviders(<BankDepositDialog pumpId={3} balance={1000} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Record bank deposit' })
    const save = within(dialog).getByRole('button', { name: 'Record deposit' })
    const user = await fillDeposit(dialog, '1200')

    expect(save).toHaveAccessibleDescription('Still needed Slip reference')
    await user.type(within(dialog).getByLabelText('Slip reference'), 'S-9')
    expect(within(dialog).getByText('The safe holds only ₹1,000.00.')).toBeInTheDocument()
    expect(figure(dialog, 'In the safe after this')).toHaveTextContent('−₹200.00')
    expect(save).toBeDisabled()
  })

  it('records the deposit with an idempotency key and then attaches the chosen slip', async () => {
    const onClose = vi.fn()
    renderWithProviders(<BankDepositDialog pumpId={3} balance={1000} onClose={onClose} />)
    const dialog = screen.getByRole('dialog', { name: 'Record bank deposit' })
    const user = await fillDeposit(dialog, '400')
    await user.type(within(dialog).getByLabelText('Slip reference'), ' S-9 ')
    const slip = new File(['%PDF-'], 'slip.pdf', { type: 'application/pdf' })
    await user.upload(within(dialog).getByLabelText(/Slip photo or PDF/), slip)
    expect(figure(dialog, 'In the safe after this')).toHaveTextContent('₹600.00')

    await user.click(within(dialog).getByRole('button', { name: 'Record deposit' }))

    expect(cashApi.recordDeposit).toHaveBeenCalledWith(3, expect.objectContaining({
      amount: 400, bankAccount: 'SBI ••1234', slipReference: 'S-9',
    }), expect.stringMatching(/^[0-9a-f]{32}$/))
    expect(cashApi.attachDepositSlip).toHaveBeenCalledWith(3, 12, slip)
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses a slip file that is not a photo or PDF', async () => {
    renderWithProviders(<BankDepositDialog pumpId={3} balance={1000} onClose={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: 'Record bank deposit' })
    const user = userEvent.setup({ applyAccept: false })
    await user.upload(within(dialog).getByLabelText(/Slip photo or PDF/), new File(['x'], 'slip.txt', { type: 'text/plain' }))
    expect(within(dialog).getByText('Choose a JPEG or PNG photo or a PDF.')).toBeInTheDocument()
  })
})
