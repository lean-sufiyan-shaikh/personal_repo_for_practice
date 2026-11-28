import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { EMPTY_COUNTS } from '../../../components/cash/denominations'
import type { DenominationCounts } from '../../../components/cash/denominations'
import { PaymentSection } from './PaymentSection'

function Payments({ restInCashPaise = 100000, cashDropped = 0, counted = false }: { restInCashPaise?: number; cashDropped?: number; counted?: boolean }) {
  const [cash, setCash] = useState('')
  const [cashCounts, setCashCounts] = useState<DenominationCounts | null>(counted ? EMPTY_COUNTS : null)
  const [upi, setUpi] = useState('')
  return (
    <PaymentSection
      cash={cash} cashCounts={cashCounts} upi={upi} card="" fleetCard=""
      onCashChange={setCash} onCashCountsChange={setCashCounts} onUpiChange={setUpi} onCardChange={() => {}} onFleetCardChange={() => {}}
      restInCashPaise={restInCashPaise} cashDropped={cashDropped} creditTotal={0}
    />
  )
}

describe('PaymentSection', () => {
  it('lists cash first among the payments', () => {
    render(<Payments />)

    const paymentNames = within(screen.getByRole('region', { name: 'Payments' })).getAllByRole('spinbutton')
      .map((input) => (input as HTMLInputElement).labels?.[0]?.textContent)
    expect(paymentNames).toEqual(['Cash (₹)', 'UPI (₹)', 'Card (₹)', 'Fleet card (₹)'])
  })

  it('fills cash with what is still due and hides Fill rest once cash holds it', async () => {
    const user = userEvent.setup()
    render(<Payments restInCashPaise={52950} />)

    await user.click(screen.getByRole('button', { name: 'Fill rest · ₹529.50' }))

    expect(screen.getByLabelText('Cash (₹)')).toHaveValue(529.5)
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()
  })

  it('offers no Fill rest when nothing is left to collect in cash or the bag is counted by note', () => {
    const { unmount } = render(<Payments restInCashPaise={0} />)
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()
    unmount()

    render(<Payments counted />)
    expect(screen.queryByRole('button', { name: /Fill rest/ })).not.toBeInTheDocument()
  })

  it('shows the drops already in the safe and asks for the cash in the bag', () => {
    render(<Payments restInCashPaise={40000} cashDropped={600} />)

    const figures = screen.getByLabelText('Cash in the safe and in the bag')
    expect(within(figures).getByText('Dropped to safe').nextSibling).toHaveTextContent('₹600.00')
    expect(within(figures).getByText('Expected in bag').nextSibling).toHaveTextContent('₹400.00')
    expect(screen.getByLabelText('Cash in bag (₹)')).toBeInTheDocument()
  })
})
