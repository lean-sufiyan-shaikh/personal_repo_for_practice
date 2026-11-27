import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import { creditApi } from '../../../api/creditApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { addDays, istDateString } from '../../../utils/date'
import { OverrideHoldModal } from './OverrideHoldModal'
import { PromiseModal } from './PromiseModal'

vi.mock('../../../api/collectionApi', () => ({ collectionApi: { recordPromise: vi.fn() } }))
vi.mock('../../../api/creditApi', () => ({ creditApi: { createExtension: vi.fn() } }))

const PUMP_ID = 3

describe('PromiseModal', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.recordPromise).mockResolvedValue({
      id: 1, clientId: 40, clientName: 'Acme', amount: 5000, promisedDate: istDateString(), recordedOn: istDateString(),
      note: null, status: 'OPEN', paidAmount: null, recordedByName: 'Manager', recordedAt: '2026-11-27T05:00:00Z', resolvedAt: null,
    })
  })

  it('records the amount and date and says reminders pause until then', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderWithProviders(<PromiseModal pumpId={PUMP_ID} clientId={40} clientName="Acme" outstanding={8000} onClose={onClose} />)

    expect(screen.getByText(/Reminders pause until the promised date/)).toBeInTheDocument()
    const amount = screen.getByLabelText('Amount promised')
    expect(amount).toHaveValue(8000)
    await user.clear(amount)
    await user.type(amount, '5000')
    const date = addDays(istDateString(), 3)
    await user.clear(screen.getByLabelText('Will pay by'))
    await user.type(screen.getByLabelText('Will pay by'), date)
    await user.click(screen.getByRole('button', { name: 'Save promise' }))

    expect(collectionApi.recordPromise).toHaveBeenCalledWith(PUMP_ID, 40, { amount: 5000, promisedDate: date, note: undefined })
    expect(onClose).toHaveBeenCalled()
  })

  it('refuses an empty amount before calling the server', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PromiseModal pumpId={PUMP_ID} clientId={40} clientName="Acme" outstanding={0} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Save promise' }))

    expect(screen.getByText('Enter an amount more than zero.')).toBeInTheDocument()
    expect(collectionApi.recordPromise).not.toHaveBeenCalled()
  })
})

describe('OverrideHoldModal', () => {
  beforeEach(() => {
    vi.mocked(creditApi.createExtension).mockResolvedValue({
      id: 9, pumpId: PUMP_ID, clientId: 40, extensionType: 'CREDIT_HOLD_OVERRIDE', extensionAmount: null,
      expiryDate: addDays(istDateString(), 7), grantedByUserId: 1, reason: 'Pays Friday', status: 'ACTIVE', createdAt: '2026-11-27T05:00:00Z',
    })
  })

  it('needs a reason, then lifts the hold until the date as a credit hold override', async () => {
    const user = userEvent.setup()
    renderWithProviders(<OverrideHoldModal pumpId={PUMP_ID} clientId={40} clientName="Acme" onClose={vi.fn()} />)

    expect(screen.getByText(/recorded in the audit log/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Override hold' }))
    expect(screen.getByText('Give a reason for the override.')).toBeInTheDocument()
    expect(creditApi.createExtension).not.toHaveBeenCalled()

    await user.type(screen.getByLabelText('Reason'), 'Pays Friday')
    await user.click(screen.getByRole('button', { name: 'Override hold' }))

    expect(creditApi.createExtension).toHaveBeenCalledWith(PUMP_ID, 40, {
      extensionType: 'CREDIT_HOLD_OVERRIDE', expiryDate: addDays(istDateString(), 7), reason: 'Pays Friday',
    })
  })
})
