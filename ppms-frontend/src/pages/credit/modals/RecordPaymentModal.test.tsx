import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError, AxiosHeaders } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../../api/creditApi'
import type { CreditClient } from '../../../api/creditApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { RecordPaymentModal } from './RecordPaymentModal'

vi.mock('../../../api/creditApi', () => ({
  creditApi: { recordPayment: vi.fn() },
}))

const PUMP_ID = 3
const client: CreditClient = {
  id: 40, pumpId: PUMP_ID, name: 'Acme Transport', phone: '9876543210', notes: null,
  creditLimit: 50_000, outstandingBalance: 12_000, outstandingInterest: 0, totalInterestRecovered: 0,
  monthlyInterestRate: 0, interestGraceDays: 1, createdAt: '2026-01-01T00:00:00Z',
  parentClientId: null, parentClientName: null, isParent: false,
}

const DUPLICATE_MESSAGE = 'Possible duplicate: a payment with reference UTR-777 (₹500.00, dated 2026-11-20) was already recorded '
  + 'in the last 90 days. Check it, then record again to confirm.'

function possibleDuplicate() {
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 400, statusText: '', headers: {}, config: { headers: new AxiosHeaders() },
    data: { code: 'CREDIT_PAYMENT_POSSIBLE_DUPLICATE', message: DUPLICATE_MESSAGE,
      params: { referenceNo: 'UTR-777', amount: '500.00', paidAt: '2026-11-20' } },
  })
}

async function chooseMode(user: ReturnType<typeof userEvent.setup>, mode: string) {
  await user.click(screen.getByRole('button', { name: /Payment mode/ }))
  await user.click(screen.getByRole('option', { name: mode }))
}

describe('RecordPaymentModal references and cheques', () => {
  beforeEach(() => {
    vi.mocked(creditApi.recordPayment).mockReset()
    vi.mocked(creditApi.recordPayment).mockResolvedValue({} as never)
  })

  it('needs the reference of a UPI payment before it can be recorded', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RecordPaymentModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Amount'), '1000')
    await chooseMode(user, 'UPI')
    const submit = screen.getByRole('button', { name: 'Record payment' })
    expect(submit).toHaveAccessibleDescription('Still needed Reference (UTR / transaction ID)')

    await user.type(screen.getByLabelText('Reference (UTR / transaction ID)'), ' UTR-1 ')
    await user.click(submit)

    expect(creditApi.recordPayment).toHaveBeenCalledWith(PUMP_ID, client.id,
      expect.objectContaining({ paymentMode: 'UPI', referenceNo: 'UTR-1' }), expect.any(String))
  })

  it('sends the cheque number, bank and date of a cheque', async () => {
    const user = userEvent.setup()
    renderWithProviders(<RecordPaymentModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Amount'), '2000')
    await chooseMode(user, 'Cheque')
    await user.type(screen.getByLabelText('Cheque number'), '000123')
    await user.type(screen.getByLabelText(/^Bank/), 'HDFC')
    await user.type(screen.getByLabelText(/^Cheque date/), '2026-12-05')
    await user.click(screen.getByRole('button', { name: 'Record payment' }))

    expect(creditApi.recordPayment).toHaveBeenCalledWith(PUMP_ID, client.id, expect.objectContaining({
      paymentMode: 'CHEQUE', referenceNo: '000123', chequeBank: 'HDFC', chequeDate: '2026-12-05',
    }), expect.any(String))
  })

  it('warns about a possible duplicate and records it with the same key once confirmed', async () => {
    const user = userEvent.setup()
    vi.mocked(creditApi.recordPayment).mockRejectedValueOnce(possibleDuplicate())
    renderWithProviders(<RecordPaymentModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Amount'), '500')
    await chooseMode(user, 'Bank transfer')
    await user.type(screen.getByLabelText('Reference (UTR / transaction ID)'), 'UTR-777')
    await user.click(screen.getByRole('button', { name: 'Record payment' }))

    expect(await screen.findByText('Possible duplicate')).toBeInTheDocument()
    expect(screen.getByText(DUPLICATE_MESSAGE)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record anyway' }))

    const [first, second] = vi.mocked(creditApi.recordPayment).mock.calls
    expect(first[2].confirmDuplicate).toBeUndefined()
    expect(second[2]).toEqual(expect.objectContaining({ referenceNo: 'UTR-777', confirmDuplicate: true }))
    expect(second[3]).not.toBe(first[3])
  })

  it('resends a failed payment with the same idempotency key so it is not recorded twice', async () => {
    const user = userEvent.setup()
    vi.mocked(creditApi.recordPayment).mockRejectedValueOnce(new AxiosError('timeout', 'ECONNABORTED'))
    renderWithProviders(<RecordPaymentModal client={client} pumpId={PUMP_ID} onClose={vi.fn()} />)

    await user.type(screen.getByLabelText('Amount'), '1000')
    await user.click(screen.getByRole('button', { name: 'Record payment' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Record payment' }))

    const [first, second] = vi.mocked(creditApi.recordPayment).mock.calls
    expect(first[3]).toMatch(/^[0-9a-f]{32}$/)
    expect(second[3]).toBe(first[3])
  })
})
