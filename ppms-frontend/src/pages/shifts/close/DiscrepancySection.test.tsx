import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DiscrepancySection } from './DiscrepancySection'

describe('DiscrepancySection', () => {
  it('offers to round a small shortage into cash', async () => {
    const onRoundOff = vi.fn()
    render(<DiscrepancySection diff={-0.6} canRoundOff onRoundOff={onRoundOff} reason="" onReasonChange={vi.fn()} />)

    expect(screen.getByText('Shift is short by ₹0.60')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Round off ₹0.60 into cash' }))
    expect(onRoundOff).toHaveBeenCalledOnce()
  })

  it('offers the same for a small excess', () => {
    render(<DiscrepancySection diff={0.5} canRoundOff onRoundOff={vi.fn()} reason="" onReasonChange={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Round off ₹0.50 into cash' })).toBeInTheDocument()
  })

  it('asks only for a reason when the gap is too large to round off', async () => {
    const onReasonChange = vi.fn()
    render(<DiscrepancySection diff={-100} canRoundOff={false} onRoundOff={vi.fn()} reason="" onReasonChange={onReasonChange} />)

    expect(screen.getByText('Shift is short by ₹100.00')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Round off/ })).not.toBeInTheDocument()
    await userEvent.setup().type(screen.getByRole('textbox'), 'x')
    expect(onReasonChange).toHaveBeenCalledWith('x')
  })
})
