import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NumericInput } from './NumericInput'

describe('NumericInput', () => {
  it('opens the decimal keypad with a Next key', () => {
    render(<NumericInput aria-label="Cash" />)
    const input = screen.getByRole('spinbutton', { name: 'Cash' })
    expect(input).toHaveAttribute('inputmode', 'decimal')
    expect(input).toHaveAttribute('enterkeyhint', 'next')
  })

  it('moves to the next field on Enter instead of submitting the form', async () => {
    const onSubmit = vi.fn((event: React.FormEvent) => event.preventDefault())
    const user = userEvent.setup()
    render(
      <form onSubmit={onSubmit}>
        <NumericInput aria-label="Cash" />
        <input type="checkbox" aria-label="Verified" />
        <NumericInput aria-label="UPI" disabled />
        <NumericInput aria-label="Card" />
        <button type="submit">Save</button>
      </form>,
    )

    await user.type(screen.getByLabelText('Cash'), '500{Enter}')
    expect(screen.getByLabelText('Card')).toHaveFocus()
    expect(onSubmit).not.toHaveBeenCalled()

    await user.keyboard('{Enter}')
    expect(onSubmit).toHaveBeenCalledOnce()
  })

  it('shows the unit beside the value without changing the field name', () => {
    render(<NumericInput aria-label="Petrol end reading" unit="L" />)
    expect(screen.getByRole('spinbutton', { name: 'Petrol end reading' })).toBeInTheDocument()
    expect(screen.getByText('L')).toHaveAttribute('aria-hidden', 'true')
  })
})
