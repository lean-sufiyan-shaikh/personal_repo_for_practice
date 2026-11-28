import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CreditAmountField } from './CreditAmountField'

const handlers = { onAmountMode: vi.fn(), onLitresMode: vi.fn(), onAmountChange: vi.fn(), onLitresChange: vi.fn() }

describe('CreditAmountField', () => {
  it('works out the litres from an amount at the shift price', () => {
    render(<CreditAmountField mode="amount" amount="250" liters="" price={100} unit="L" {...handlers} />)

    expect(screen.getByLabelText('Amount')).toHaveAccessibleDescription('2.50 L × ₹100.00 = ₹250.00')
  })

  it('shows the amount worked out from litres', () => {
    render(<CreditAmountField mode="liters" amount="350" liters="3.5" price={100} unit="L" {...handlers} />)

    expect(screen.getByLabelText('Quantity')).toHaveAccessibleDescription('3.50 L × ₹100.00 = ₹350.00')
  })

  it('offers no litre entry and no worked-out line without a price', () => {
    render(<CreditAmountField mode="amount" amount="250" liters="" price={null} unit="L" {...handlers} />)

    expect(screen.getByLabelText('Amount')).not.toHaveAccessibleDescription()
    expect(screen.getByRole('button', { pressed: false })).toBeDisabled()
  })
})
