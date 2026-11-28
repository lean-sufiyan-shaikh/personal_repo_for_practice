import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'
import { PortalBalanceResult } from './PortalBalanceResult'
import type { PortalBalance } from './PortalBalanceResult'

vi.mock('qrcode', () => ({ default: { toString: vi.fn().mockResolvedValue('<svg>qr</svg>') } }))

const mainAccount: PortalBalance = {
  outstandingAmount: 8200, creditLimit: 20000, unlimited: false, subAccount: false, upiPayLink: null, vehicleUsage: [],
}

describe('PortalBalanceResult', () => {
  resetScreenWidthAfterEach()

  it('shows a main account the group balance and limit', () => {
    render(<PortalBalanceResult result={mainAccount} />)

    expect(screen.getByText('Outstanding balance').nextSibling).toHaveTextContent('₹8,200.00')
    expect(screen.getByText('Credit limit').nextSibling).toHaveTextContent('₹20,000.00')
  })

  it('says there is no limit for an unlimited account and that a zero balance is settled', () => {
    render(<PortalBalanceResult result={{ ...mainAccount, outstandingAmount: 0, creditLimit: null, unlimited: true }} />)

    expect(screen.getByText('No limit')).toBeInTheDocument()
    expect(screen.getByText('Your account is fully settled.')).toBeInTheDocument()
  })

  it('shows a sub-account its own usage and says the balance is held on the main account', () => {
    render(<PortalBalanceResult result={{ ...mainAccount, outstandingAmount: 1500, creditLimit: null, subAccount: true }} />)

    expect(screen.getByText('Credit used on this account').nextSibling).toHaveTextContent('₹1,500.00')
    expect(screen.getByText('This is a sub-account. The account balance and limit are held on the main account.')).toBeInTheDocument()
    expect(screen.queryByText('Credit limit')).not.toBeInTheDocument()
    expect(screen.queryByText('Your account is fully settled.')).not.toBeInTheDocument()
  })

  it('shows a UPI QR code for the balance and, on a phone only, a Pay via UPI button', async () => {
    setScreenWidth(390)
    const link = 'upi://pay?pa=ramfuels@okaxis&pn=Ram%20Fuels&am=1250.50&cu=INR&tn=Acme'
    const { unmount } = render(<PortalBalanceResult result={{ ...mainAccount, outstandingAmount: 1250.5, upiPayLink: link }} />)

    expect(screen.getByRole('link', { name: 'Pay via UPI' })).toHaveAttribute('href', link)
    expect(await screen.findByRole('img', { name: 'UPI QR code to pay ₹1,250.50' })).toBeInTheDocument()
    expect(screen.getByText(/once the pump records it/)).toBeInTheDocument()
    unmount()

    setScreenWidth(1280)
    render(<PortalBalanceResult result={{ ...mainAccount, outstandingAmount: 1250.5, upiPayLink: link }} />)
    expect(screen.queryByRole('link', { name: 'Pay via UPI' })).not.toBeInTheDocument()
  })

  it('offers no payment when the pump has no UPI details', () => {
    render(<PortalBalanceResult result={mainAccount} />)

    expect(screen.queryByRole('link', { name: 'Pay via UPI' })).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('shows the fuel each vehicle took this month and last month, with credit for unknown vehicles apart', () => {
    render(<PortalBalanceResult result={{ ...mainAccount, vehicleUsage: [
      { month: '2026-11', vehicles: [{ registration: 'MH12AB1234', litres: 30, amount: 3000, entryCount: 2 }] },
      { month: '2026-10', vehicles: [{ registration: null, litres: 0, amount: 450, entryCount: 1 }] },
    ] }} />)

    expect(screen.getByRole('heading', { name: 'Credit by vehicle' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'November 2026' })).toBeInTheDocument()
    expect(screen.getByText('MH12AB1234')).toBeInTheDocument()
    expect(screen.getByText('30.00 L · ₹3,000.00')).toBeInTheDocument()
    expect(screen.getByText('Unknown or unrecorded vehicle')).toBeInTheDocument()
    expect(screen.getByText('₹450.00')).toBeInTheDocument()
  })
})
