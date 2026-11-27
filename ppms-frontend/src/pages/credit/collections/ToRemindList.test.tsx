import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import type { ReminderDue } from '../../../api/collectionApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { ToRemindList } from './ToRemindList'

vi.mock('../../../api/collectionApi', () => ({
  collectionApi: { recordReminder: vi.fn() },
}))

const PUMP_ID = 3

const due: ReminderDue = {
  clientId: 40, clientName: 'Acme Transport', phone: '98765 43210', outstanding: 12500, daysOverdue: 16,
  overdueSince: '2026-11-12', stageDays: 15, onHold: false, lastRemindedAt: '2026-11-20T05:00:00Z',
  lastReminderMethod: 'CALL', upiPayLink: 'upi://pay?pa=ramfuels@okaxis&pn=Ram%20Fuels&am=12500.00&cu=INR&tn=Acme',
}

describe('ToRemindList', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.recordReminder).mockResolvedValue({
      id: 1, clientId: 40, overdueSince: '2026-11-12', stageDays: 15, daysOverdue: 16, outstanding: 12500,
      channel: 'MANUAL', method: 'MESSAGE_COPIED', note: null, remindedByName: 'Manager', remindedAt: '2026-11-27T05:00:00Z',
    })
  })

  it('shows a ready message with the balance, days overdue, balance page and UPI link, and a call link', async () => {
    renderWithProviders(<ToRemindList pumpId={PUMP_ID} reminders={[due]} canRemind />)

    const card = screen.getByRole('listitem')
    expect(within(card).getByText('Acme Transport')).toBeInTheDocument()
    expect(within(card).getByText('15-day reminder')).toBeInTheDocument()
    expect(within(card).getByText(/Last reminded .* · Phone call/)).toBeInTheDocument()
    const message = within(card).getByLabelText('Reminder message')
    expect(message).toHaveTextContent('Namaste Acme Transport, ₹12,500.00 is outstanding on your credit account, 16 days overdue.')
    expect(message).toHaveTextContent(`${window.location.origin}/portal/credit-balance?pump=3`)
    expect(message).toHaveTextContent('Pay by UPI: upi://pay?pa=ramfuels@okaxis')
    expect(within(card).getByRole('link', { name: 'Call' })).toHaveAttribute('href', 'tel:9876543210')
  })

  it('copies the message and records the reminder as the copied message', async () => {
    const user = userEvent.setup()
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    renderWithProviders(<ToRemindList pumpId={PUMP_ID} reminders={[due]} canRemind />)

    await user.click(screen.getByRole('button', { name: 'Copy message' }))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('16 days overdue'))

    await user.click(screen.getByRole('button', { name: 'Mark reminded' }))
    const dialog = await screen.findByRole('dialog', { name: 'Mark reminded' })
    expect(within(dialog).getByRole('radio', { name: 'Sent the copied message' })).toBeChecked()
    await user.type(within(dialog).getByRole('textbox'), 'Sent on SMS')
    await user.click(within(dialog).getByRole('button', { name: 'Save reminder' }))

    expect(collectionApi.recordReminder).toHaveBeenCalledWith(PUMP_ID, 40, 'MESSAGE_COPIED', 'Sent on SMS')
  })

  it('marks an on-hold client and leaves out Mark reminded for a reader', () => {
    renderWithProviders(<ToRemindList pumpId={PUMP_ID} reminders={[{ ...due, onHold: true, phone: null }]} canRemind={false} />)

    expect(screen.getByText('On hold')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Call' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark reminded' })).not.toBeInTheDocument()
  })

  it('says when nobody needs a reminder', () => {
    renderWithProviders(<ToRemindList pumpId={PUMP_ID} reminders={[]} canRemind />)

    expect(screen.getByText('Nobody needs a reminder today.')).toBeInTheDocument()
  })
})
