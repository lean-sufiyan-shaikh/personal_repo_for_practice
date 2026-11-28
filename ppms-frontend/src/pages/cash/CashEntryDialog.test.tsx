import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cashApi } from '../../api/cashApi'
import { useAuthStore } from '../../store/authStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { CashEntryDialog } from './CashEntryDialog'

vi.mock('../../api/cashApi', () => ({ cashApi: { recordCashEvent: vi.fn() } }))

function openForm() {
  renderWithProviders(<CashEntryDialog pumpId={3} balance={2000} onClose={vi.fn()} />)
  return screen.getByRole('dialog', { name: 'Record cash movement' })
}

async function chooseCashOut(user: ReturnType<typeof userEvent.setup>, form: HTMLElement) {
  await user.click(within(form).getByRole('button', { name: /^Event type/ }))
  await user.click(screen.getByRole('option', { name: 'Cash out' }))
}

function figure(form: HTMLElement, label: string) {
  return within(form).getByText(label).nextElementSibling
}

describe('CashEntryDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({ user: { userId: 1, fullName: 'Manager', role: 'MANAGER', assignedPumpId: 3 }, isAuthenticated: true })
  })

  it('keeps Review disabled and says what is still needed until amount and description are in', async () => {
    const user = userEvent.setup()
    const form = openForm()
    const review = within(form).getByRole('button', { name: 'Review' })

    expect(review).toBeDisabled()
    expect(review).toHaveAccessibleDescription('Still needed Amount Description')

    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('500')
    expect(review).toHaveAccessibleDescription('Still needed Description')
    await user.click(within(form).getByLabelText('Description'))
    await user.paste('Change float')
    expect(review).toBeEnabled()
    expect(review).not.toHaveAccessibleDescription()
  })

  it('shows what will be in the safe after a cash-in or cash-out as the amount is typed', async () => {
    const user = userEvent.setup()
    const form = openForm()

    expect(figure(form, 'In the safe after this')).toHaveTextContent('—')
    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('750')
    expect(figure(form, 'In the safe after this')).toHaveTextContent('₹2,750.00')

    await chooseCashOut(user, form)
    expect(figure(form, 'In the safe after this')).toHaveTextContent('₹1,250.00')
  })

  it('refuses a cash-out larger than the drawer balance', async () => {
    const user = userEvent.setup()
    const form = openForm()
    await chooseCashOut(user, form)
    await user.click(within(form).getByLabelText('Amount'))
    await user.paste('2500')
    await user.click(within(form).getByLabelText('Description'))
    await user.paste('Bank deposit')
    await user.click(within(form).getByRole('button', { name: 'Review' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('exceeds the current drawer balance')
    expect(screen.queryByRole('dialog', { name: 'Review cash movement' })).not.toBeInTheDocument()
    expect(cashApi.recordCashEvent).not.toHaveBeenCalled()
  })
})
