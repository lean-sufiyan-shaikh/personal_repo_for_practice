import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import type { ClientFollowUp, PaymentPromise } from '../../../api/collectionApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { addDays, istDateString } from '../../../utils/date'
import { CancelPromiseModal } from './CancelPromiseModal'
import { ClientFollowUpTab } from './ClientFollowUpTab'
import { PromiseModal } from './PromiseModal'

vi.mock('../../../api/collectionApi', () => ({
  collectionApi: { getClientFollowUp: vi.fn(), recordPromise: vi.fn(), editPromise: vi.fn(), cancelPromise: vi.fn() },
}))
vi.mock('../../../api/creditApi', () => ({ creditApi: { createExtension: vi.fn() } }))

const PUMP_ID = 3
const openPromise: PaymentPromise = {
  id: 4, clientId: 40, clientName: 'Acme', amount: 3000, promisedDate: addDays(istDateString(), 5),
  recordedOn: istDateString(), note: 'Said Friday', status: 'OPEN', paidAmount: null, recordedByName: 'Meera',
  recordedAt: '2026-11-27T05:00:00Z', resolvedAt: null,
}
const followUp: ClientFollowUp = {
  clientId: 40, outstanding: 8000, daysOverdue: 10, overdueSince: '2026-11-17', reachedStage: 7, reminderDue: false,
  creditHoldDays: 45, onHold: false, upiPayLink: null, promises: [openPromise], reminders: [],
}

describe('changing and cancelling a promise', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.editPromise).mockReset().mockResolvedValue(openPromise)
    vi.mocked(collectionApi.cancelPromise).mockReset().mockResolvedValue({ ...openPromise, status: 'CANCELLED' })
    vi.mocked(collectionApi.getClientFollowUp).mockResolvedValue(followUp)
  })

  it('offers change and cancel on an open promise only to those who record promises', async () => {
    const user = userEvent.setup()
    const { unmount } = renderWithProviders(
      <ClientFollowUpTab pumpId={PUMP_ID} clientId={40} clientName="Acme" phone={null} canRemind canOverrideHold={false} />)

    await user.click(await screen.findByRole('button', { name: 'Change' }))
    expect(within(screen.getByRole('dialog', { name: 'Change promise' })).getByLabelText('Amount promised')).toHaveValue(3000)
    unmount()

    renderWithProviders(
      <ClientFollowUpTab pumpId={PUMP_ID} clientId={40} clientName="Acme" phone={null} canRemind={false} canOverrideHold={false} />)
    expect(await screen.findByText(/reminders are paused until then/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel promise' })).not.toBeInTheDocument()
  })

  it('saves a changed amount, date and reason on the open promise', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(<PromiseModal pumpId={PUMP_ID} clientId={40} clientName="Acme" outstanding={8000}
      promise={openPromise} onClose={onClose} />)

    const amount = screen.getByLabelText('Amount promised')
    await user.clear(amount)
    await user.type(amount, '2500')
    const date = addDays(istDateString(), 10)
    await user.clear(screen.getByLabelText('Will pay by'))
    await user.type(screen.getByLabelText('Will pay by'), date)
    await user.type(screen.getByLabelText(/^Why it changed/), 'Asked for more time')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(collectionApi.editPromise).toHaveBeenCalledWith(PUMP_ID, 4,
      { amount: 2500, promisedDate: date, note: 'Said Friday', reason: 'Asked for more time' })
    expect(collectionApi.recordPromise).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses a date more than ninety days ahead before calling the server', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PromiseModal pumpId={PUMP_ID} clientId={40} clientName="Acme" outstanding={8000}
      promise={openPromise} onClose={vi.fn()} />)

    await user.clear(screen.getByLabelText('Will pay by'))
    await user.type(screen.getByLabelText('Will pay by'), addDays(istDateString(), 91))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    expect(screen.getByLabelText('Will pay by')).toBeInvalid()
    expect(collectionApi.editPromise).not.toHaveBeenCalled()
  })

  it('needs a reason to cancel, then cancels the promise', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(<CancelPromiseModal pumpId={PUMP_ID} clientName="Acme" promise={openPromise} onClose={onClose} />)

    expect(screen.getByText(/Reminders resume for this account/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel promise' }))
    expect(screen.getByText('A reason is required to cancel a promise.')).toBeInTheDocument()
    expect(collectionApi.cancelPromise).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Reason'), ' Disputes the bill ')
    await user.click(screen.getByRole('button', { name: 'Cancel promise' }))

    expect(collectionApi.cancelPromise).toHaveBeenCalledWith(PUMP_ID, 4, 'Disputes the bill')
    expect(onClose).toHaveBeenCalled()
  })
})
