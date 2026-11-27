import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn())
vi.mock('axios', async (importOriginal) => {
  const actual = await importOriginal<typeof import('axios')>()
  return { ...actual, default: { ...actual.default, create: () => ({ post }) } }
})

vi.mock('qrcode', () => ({ default: { toString: vi.fn().mockResolvedValue('<svg>qr</svg>') } }))

import CreditBalancePortalPage from './CreditBalancePortalPage'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'

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
  resetScreenWidthAfterEach()

  beforeEach(() => {
    post.mockReset()
  })

  it('shows a UPI QR code for the balance and, on a phone, a Pay via UPI button', async () => {
    setScreenWidth(390)
    const link = 'upi://pay?pa=ramfuels@okaxis&pn=Ram%20Fuels&am=1250.50&cu=INR&tn=Acme'
    post.mockResolvedValue({ data: { outstandingAmount: 1250.5, creditLimit: null, unlimited: true, subAccount: false, upiPayLink: link, vehicleUsage: [] } })
    await lookUp()

    expect(await screen.findByRole('link', { name: 'Pay via UPI' })).toHaveAttribute('href', link)
    expect(await screen.findByRole('img', { name: 'UPI QR code to pay ₹1,250.50' })).toBeInTheDocument()
    expect(screen.getByText(/once the pump records it/)).toBeInTheDocument()
  })

  it('keeps the pay button off a desktop and shows nothing to pay when the pump has no UPI details', async () => {
    setScreenWidth(1280)
    post.mockResolvedValue({ data: { outstandingAmount: 1250.5, creditLimit: null, unlimited: true, subAccount: false, upiPayLink: null, vehicleUsage: [] } })
    await lookUp()

    expect(await screen.findByText('₹1,250.50')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Pay via UPI' })).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
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

  it('shows the fuel each vehicle took this month and last month, with credit for unknown vehicles apart', async () => {
    setScreenWidth(390)
    post.mockResolvedValue({ data: { outstandingAmount: 500, creditLimit: null, unlimited: true, subAccount: false, upiPayLink: null, vehicleUsage: [
      { month: '2026-11', vehicles: [{ registration: 'MH12AB1234', litres: 30, amount: 3000, entryCount: 2 }] },
      { month: '2026-10', vehicles: [{ registration: null, litres: 0, amount: 450, entryCount: 1 }] },
    ] } })
    await lookUp()

    expect(await screen.findByRole('heading', { name: 'Credit by vehicle' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'November 2026' })).toBeInTheDocument()
    expect(screen.getByText('MH12AB1234')).toBeInTheDocument()
    expect(screen.getByText('30.00 L · ₹3,000.00')).toBeInTheDocument()
    expect(screen.getByText('Unknown or unrecorded vehicle')).toBeInTheDocument()
    expect(screen.getByText('₹450.00')).toBeInTheDocument()
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

  it('says to wait when there were too many attempts', async () => {
    post.mockRejectedValue(httpError(429))
    await lookUp()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please wait 15 minutes and try again.')
  })

  it('says how long the lock lasts when it has grown after repeated lockouts', async () => {
    post.mockRejectedValue(httpError(429, { minutes: 60 }))
    await lookUp()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Please wait 60 minutes and try again.')
  })

  it('shows a main account the group balance and limit', async () => {
    post.mockResolvedValue({ data: { outstandingAmount: 8200, creditLimit: 20000, unlimited: false, subAccount: false,
      upiPayLink: null, vehicleUsage: [] } })
    await lookUp('482971')

    expect(await screen.findByText('Outstanding balance')).toBeInTheDocument()
    expect(screen.getByText('₹8,200.00')).toBeInTheDocument()
    expect(screen.getByText('₹20,000.00')).toBeInTheDocument()
  })

  it('shows a sub-account its own usage and says the balance is held on the main account', async () => {
    post.mockResolvedValue({ data: { outstandingAmount: 1500, creditLimit: null, unlimited: false, subAccount: true,
      upiPayLink: null, vehicleUsage: [] } })
    await lookUp()

    expect(await screen.findByText('Credit used on this account')).toBeInTheDocument()
    expect(screen.getByText('₹1,500.00')).toBeInTheDocument()
    expect(screen.getByText('This is a sub-account. The account balance and limit are held on the main account.')).toBeInTheDocument()
    expect(screen.queryByText('Credit limit')).not.toBeInTheDocument()
    expect(screen.queryByText('Your account is fully settled.')).not.toBeInTheDocument()
  })

  it('says there is no limit for an unlimited account', async () => {
    post.mockResolvedValue({ data: { outstandingAmount: 100, creditLimit: null, unlimited: true, subAccount: false,
      upiPayLink: null, vehicleUsage: [] } })
    await lookUp()

    expect(await screen.findByText('No limit')).toBeInTheDocument()
  })

  it('does not call the server with a PIN that is too short', async () => {
    await lookUp('12')
    expect(post).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('4 to 6 digit PIN')
  })
})
