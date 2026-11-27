import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { Shift } from '../../../types/shift'
import { ShiftLitresByNozzle } from './ShiftLitresByNozzle'

const closedShift = {
  id: 9,
  nozzles: [{ id: 3, nozzleNumber: 1, fuelType: 'PETROL' }],
  fuelReadings: [{
    nozzleId: 3, fuelType: 'PETROL', startReading: 1000, endReading: 1105, priceSnapshot: 100,
    unitsSold: 100, testLitres: 5, meterLitres: 105, maxMeterValue: 999_999,
  }],
} as unknown as Shift

describe('ShiftLitresByNozzle', () => {
  it('shows the meter litres, the test litres returned and the litres sold per nozzle', () => {
    renderWithProviders(<ShiftLitresByNozzle shift={closedShift} />)

    const section = screen.getByRole('region', { name: 'Litres by nozzle' })
    expect(section).toHaveTextContent('Nozzle #1')
    expect(section).toHaveTextContent('Meter 105.00 L · Test returned 5.00 L · Sold 100.00 L')
  })

  it('shows nothing while the shift is open', () => {
    const open = { ...closedShift, fuelReadings: [{ ...closedShift.fuelReadings[0], unitsSold: null }] } as Shift
    renderWithProviders(<ShiftLitresByNozzle shift={open} />)
    expect(screen.queryByRole('region', { name: 'Litres by nozzle' })).not.toBeInTheDocument()
  })
})
