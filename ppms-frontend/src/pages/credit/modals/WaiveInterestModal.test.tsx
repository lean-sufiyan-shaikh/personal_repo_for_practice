import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../../api/creditApi'
import type { CreditTransaction } from '../../../api/creditApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { WaiveInterestModal } from './WaiveInterestModal'

vi.mock('../../../api/creditApi', () => ({
  creditApi: { waiveInterestCharge: vi.fn() },
}))

const charge: CreditTransaction = {
  type: 'INTEREST', referenceId: 31, reference: null, referenceCode: null, referenceParams: {},
  detail: '2026-10-01 – 2026-10-31', amount: 900, runningBalance: 0, occurredAt: '2026-11-01T01:00:00Z',
  businessDate: '2026-10-31', status: 'ACTIVE', countedInBalance: true,
}

function renderModal() {
  const onWaived = vi.fn()
  renderWithProviders(<WaiveInterestModal pumpId={3} clientId={40} charge={charge} onWaived={onWaived} onClose={vi.fn()} />)
  return { onWaived }
}

describe('WaiveInterestModal', () => {
  beforeEach(() => {
    vi.mocked(creditApi.waiveInterestCharge).mockReset().mockResolvedValue({} as never)
  })

  it('waives without charging the days again unless recalculating is chosen', async () => {
    const user = userEvent.setup()
    const { onWaived } = renderModal()

    expect(screen.getByLabelText(/Waive \(don't charge again\)/)).toBeChecked()
    expect(screen.getByText(/skips these days/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Reason'), 'Goodwill')
    await user.click(screen.getByRole('button', { name: 'Waive interest' }))

    expect(creditApi.waiveInterestCharge).toHaveBeenCalledWith(3, 40, 31, 'Goodwill', 'KEEP_PERIOD_CHARGED')
    expect(onWaived).toHaveBeenCalled()
  })

  it('sends the recalculate choice when picked', async () => {
    const user = userEvent.setup()
    renderModal()

    await user.click(screen.getByLabelText(/Waive and recalculate/))
    await user.type(screen.getByLabelText('Reason'), 'Wrong rate')
    await user.click(screen.getByRole('button', { name: 'Waive interest' }))

    expect(creditApi.waiveInterestCharge).toHaveBeenCalledWith(3, 40, 31, 'Wrong rate', 'RECALCULATE')
  })
})
