import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../../api/creditApi'
import type { CreditTransaction } from '../../../api/creditApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { ReversePaymentModal } from './ReversePaymentModal'

vi.mock('../../../api/creditApi', () => ({
  creditApi: { reversePayment: vi.fn() },
}))

const PUMP_ID = 3
const payment: CreditTransaction = {
  type: 'PAYMENT', referenceId: 21, reference: null, referenceCode: null, referenceParams: {}, detail: 'CHEQUE',
  amount: 4000, runningBalance: 0, occurredAt: '2026-11-03T06:00:00Z', businessDate: '2026-11-03',
  status: 'APPROVED', countedInBalance: true,
}

describe('ReversePaymentModal', () => {
  beforeEach(() => {
    vi.mocked(creditApi.reversePayment).mockReset()
    vi.mocked(creditApi.reversePayment).mockResolvedValue({} as never)
  })

  it('needs a reason before reversing', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ReversePaymentModal pumpId={PUMP_ID} payment={payment} onReversed={vi.fn()} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Reverse payment' }))

    expect(screen.getByText('A reason is required to reverse a payment')).toBeInTheDocument()
    expect(creditApi.reversePayment).not.toHaveBeenCalled()
  })

  it('shows what goes back on the account and sends the reason, the bounce charge and an idempotency key', async () => {
    const user = userEvent.setup()
    const onReversed = vi.fn()
    const onClose = vi.fn()
    renderWithProviders(<ReversePaymentModal pumpId={PUMP_ID} payment={payment} onReversed={onReversed} onClose={onClose} />)

    await user.type(screen.getByLabelText('Reason'), ' Cheque bounced ')
    await user.type(screen.getByLabelText(/^Bounce or bank charge/), '250')
    expect(screen.getByText('Added back to the account').nextSibling).toHaveTextContent('₹4,250.00')
    await user.click(screen.getByRole('button', { name: 'Reverse payment' }))

    expect(creditApi.reversePayment).toHaveBeenCalledWith(PUMP_ID, 21,
      { reason: 'Cheque bounced', bounceCharge: 250 }, expect.stringMatching(/^[0-9a-f]{32}$/))
    expect(onReversed).toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses a bounce charge of zero', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ReversePaymentModal pumpId={PUMP_ID} payment={payment} onReversed={vi.fn()} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Reason'), 'Cheque bounced')
    await user.type(screen.getByLabelText(/^Bounce or bank charge/), '0')
    await user.click(screen.getByRole('button', { name: 'Reverse payment' }))

    expect(screen.getByLabelText(/^Bounce or bank charge/)).toBeInvalid()
    expect(creditApi.reversePayment).not.toHaveBeenCalled()
  })
})
