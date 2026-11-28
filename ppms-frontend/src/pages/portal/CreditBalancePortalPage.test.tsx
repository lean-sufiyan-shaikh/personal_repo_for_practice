import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn())
vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>()
  return { ...actual, default: { ...actual.default, create: () => ({ post }) } }
})

import CreditBalancePortalPage from './CreditBalancePortalPage'

function httpError(status: number, params: Record<string, unknown> = {}) {
  return { isAxiosError: true, response: { status, data: { message: 'No credit account matches these details.', params } } }
}

async function lookUp(pin = '4821') {
  const user = userEvent.setup()
  render(<CreditBalancePortalPage />)
  await user.type(screen.getByLabelText('Pump ID'), '3')
  await user.type(screen.getByLabelText('Registered phone number'), '9876543210')
  await user.type(screen.getByLabelText('Portal PIN'), pin)
  await user.click(screen.getByRole('button', { name: 'Check balance' }))
}

describe('CreditBalancePortalPage', () => {
  beforeEach(() => {
    post.mockReset()
  })

  it('fills the pump from the link a reminder sends', () => {
    window.history.pushState({}, '', '/portal/credit-balance?pump=12')
    render(<CreditBalancePortalPage />)
    expect(screen.getByLabelText('Pump ID')).toHaveValue(12)
    window.history.pushState({}, '', '/')
  })

  it('looks the balance up with pump, phone and PIN and shows the amounts without a name', async () => {
    post.mockResolvedValue({ data: { outstandingAmount: 1250.5, creditLimit: 50000, unlimited: false, subAccount: false, upiPayLink: null, vehicleUsage: [] } })
    await lookUp()

    expect(post).toHaveBeenCalledWith('/public/credit-balance', { pumpId: 3, phone: '9876543210', pin: '4821' })
    expect(await screen.findByText('₹1,250.50')).toBeInTheDocument()
    expect(screen.getByText('₹50,000.00')).toBeInTheDocument()
    expect(screen.queryByText(/Account found for/)).not.toBeInTheDocument()
  })

  it('masks the PIN and asks for digits', () => {
    render(<CreditBalancePortalPage />)
    const pin = screen.getByLabelText('Portal PIN')
    expect(pin).toHaveAttribute('type', 'password')
    expect(pin).toHaveAttribute('inputmode', 'numeric')
    expect(pin).toHaveAttribute('autocomplete', 'off')
  })

  it('gives one generic answer when the details do not match', async () => {
    post.mockRejectedValue(httpError(404))
    await lookUp()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Details don't match. Check your phone number and PIN, or ask the pump for a PIN.",
    )
  })

  it('says how long the lock lasts when it has grown after repeated lockouts', async () => {
    post.mockRejectedValue(httpError(429, { minutes: 60 }))
    await lookUp()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please wait 60 minutes and try again.')
  })

  it('does not call the server with a PIN that is too short', async () => {
    await lookUp('12')
    expect(post).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('4 to 6 digit PIN')
  })
})
