import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import type { ClientFollowUp } from '../../../api/collectionApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { ClientFollowUpTab } from './ClientFollowUpTab'

vi.mock('../../../api/collectionApi', () => ({
  collectionApi: { getClientFollowUp: vi.fn(), recordReminder: vi.fn(), recordPromise: vi.fn() },
}))
vi.mock('../../../api/creditApi', () => ({ creditApi: { createExtension: vi.fn() } }))
vi.mock('qrcode', () => ({ default: { toString: vi.fn().mockResolvedValue('<svg>qr</svg>') } }))

const held: ClientFollowUp = {
  clientId: 40, outstanding: 8000, daysOverdue: 50, overdueSince: '2026-10-09', reachedStage: 30, reminderDue: true,
  creditHoldDays: 45, onHold: true, upiPayLink: 'upi://pay?pa=ramfuels@okaxis&pn=Ram&am=8000.00&cu=INR&tn=Acme',
  promises: [{
    id: 4, clientId: 40, clientName: 'Acme', amount: 3000, promisedDate: '2026-11-20', recordedOn: '2026-11-15', note: 'Said Friday',
    status: 'MISSED', paidAmount: 0, recordedByName: 'Meera', recordedAt: '2026-11-15T05:00:00Z', resolvedAt: '2026-11-21T01:00:00Z',
  }],
  reminders: [{
    id: 2, clientId: 40, overdueSince: '2026-10-09', stageDays: 15, daysOverdue: 16, outstanding: 8000, channel: 'MANUAL',
    method: 'IN_PERSON', note: null, remindedByName: 'Meera', remindedAt: '2026-10-25T06:00:00Z',
  }],
}

function renderTab(followUp: ClientFollowUp, roles = { canRemind: true, canOverrideHold: true }) {
  vi.mocked(collectionApi.getClientFollowUp).mockResolvedValue(followUp)
  return renderWithProviders(
    <ClientFollowUpTab pumpId={3} clientId={40} clientName="Acme" phone="9876543210" {...roles} />,
  )
}

describe('ClientFollowUpTab', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.getClientFollowUp).mockReset()
  })

  it('shows the hold with an override for owners, the reminder due, a UPI QR code and the history', async () => {
    renderTab(held)

    expect(await screen.findByText(/Credit on hold: 50 days overdue \(held from 45 days\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Override hold' })).toBeInTheDocument()
    expect(screen.getByText('30-day reminder')).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: 'UPI QR code to pay ₹8,000.00' })).toHaveAttribute('src', expect.stringContaining('data:image/svg+xml'))
    expect(screen.getByText('Missed')).toBeInTheDocument()
    expect(screen.getByText('In person by Meera at 16 days overdue')).toBeInTheDocument()
  })

  it('opens the override dialog', async () => {
    const user = userEvent.setup()
    renderTab(held)

    await user.click(await screen.findByRole('button', { name: 'Override hold' }))

    expect(screen.getByRole('dialog', { name: 'Override credit hold' })).toBeInTheDocument()
  })

  it('hides the override from managers and the promise button while a promise is open', async () => {
    renderTab({
      ...held, onHold: false, daysOverdue: 20, reminderDue: false, upiPayLink: null,
      promises: [{ ...held.promises[0], status: 'OPEN', paidAmount: null, promisedDate: '2026-11-30' }],
    }, { canRemind: true, canOverrideHold: false })

    expect(await screen.findByText(/reminders are paused until then/)).toBeInTheDocument()
    expect(screen.getByText('Credit is held at 45 days overdue.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Override hold' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record promise' })).not.toBeInTheDocument()
  })

  it('offers to record a promise for an overdue account without one', async () => {
    const user = userEvent.setup()
    renderTab({ ...held, onHold: false, promises: [] })

    await user.click(await screen.findByRole('button', { name: 'Record promise' }))

    const dialog = screen.getByRole('dialog', { name: 'Promise to pay' })
    expect(within(dialog).getByLabelText('Amount promised')).toHaveValue(8000)
  })
})
