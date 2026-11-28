import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ReadingField } from './CloseShiftFields'

const nozzle = { label: 'Nozzle #1 Petrol', startValue: 1000, pricePerUnit: 100, unitLabel: 'L' }

function endReading() {
  return screen.getByRole('spinbutton', { name: 'Nozzle #1 Petrol end reading' })
}

describe('ReadingField', () => {
  it('names the closing meter reading after the nozzle and shows the start and price', () => {
    render(<ReadingField {...nozzle} soldUnits={0} value="" onChange={vi.fn()} />)

    expect(endReading()).toBeInTheDocument()
    expect(screen.getByText('Start 1,000 L · ₹100.00/L')).toBeInTheDocument()
    expect(endReading()).not.toHaveAccessibleDescription()
  })

  it('shows the sale of the nozzle once an end reading is typed', () => {
    render(<ReadingField {...nozzle} soldUnits={12.5} value="1012.5" onChange={vi.fn()} />)

    expect(endReading()).toHaveAccessibleDescription('= 12.50 L × ₹100.00 = ₹1,250.00')
  })

  it('explains an end reading below the start instead of a sale line', () => {
    render(<ReadingField {...nozzle} soldUnits={0} value="990" isInvalid onChange={vi.fn()} />)

    expect(screen.getByRole('alert')).toHaveTextContent('End reading cannot be less than start (1000)')
    expect(endReading()).toHaveAttribute('aria-invalid', 'true')
    expect(screen.queryByText(/× ₹100\.00/)).not.toBeInTheDocument()
  })

  it('locks the reading of a nozzle marked No change and says nothing was sold', async () => {
    const onNoChangeToggle = vi.fn()
    const { rerender } = render(<ReadingField {...nozzle} soldUnits={0} value="" onChange={vi.fn()} onNoChangeToggle={onNoChangeToggle} />)
    await userEvent.setup().click(screen.getByRole('checkbox', { name: 'No change' }))
    expect(onNoChangeToggle).toHaveBeenCalledOnce()

    rerender(<ReadingField {...nozzle} soldUnits={0} value="1000" noChange onChange={vi.fn()} onNoChangeToggle={onNoChangeToggle} />)
    expect(endReading()).toBeDisabled()
    expect(screen.getByText('No change · 0 L sold')).toBeInTheDocument()
  })
})
