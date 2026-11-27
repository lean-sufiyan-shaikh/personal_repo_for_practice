import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { CloseShiftSummary } from './CloseShiftSummary'

const closedFigures = {
  incomingOperatorName: null, totalDue: 3200, totalCollected: 3200, diff: 0, creditEntryCount: 0, isEscalated: false,
  printError: null, roundingAdjustment: 0, priceChangeLines: [],
}

describe('CloseShiftSummary', () => {
  it('lists the litres sold at each price when the price changed during the shift', () => {
    renderWithProviders(<CloseShiftSummary {...closedFigures}
      priceChangeLines={[{ label: 'Nozzle #1 · Petrol', text: '10.00 L × ₹100.00 + 20.00 L × ₹110.00' }]} />)

    expect(screen.getByText('The price changed during this shift')).toBeInTheDocument()
    expect(screen.getByText('Nozzle #1 · Petrol: 10.00 L × ₹100.00 + 20.00 L × ₹110.00')).toBeInTheDocument()
  })

  it('says a small difference closed as rounding instead of showing it as short', () => {
    renderWithProviders(<CloseShiftSummary {...closedFigures} totalCollected={3199.5} diff={-0.5} roundingAdjustment={-0.5} />)

    expect(screen.getByText('A difference of ₹0.50 was within the rounding allowance, so the shift balanced.')).toBeInTheDocument()
    expect(screen.queryByText(/listed under Discrepancies/)).not.toBeInTheDocument()
  })
})
