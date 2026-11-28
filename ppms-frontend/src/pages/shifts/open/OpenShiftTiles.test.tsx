import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { DUOption, NozzleDetail } from '../../../types/shift'
import { DispenserTile } from './DispenserTile'
import { NozzleTile } from './NozzleTile'

function nozzle(id: number, nozzleNumber: number, fuelType: NozzleDetail['fuelType'], lastReading = 1000): NozzleDetail {
  return { id, nozzleNumber, fuelType, lastReading, maxMeterValue: 999_999, tankId: 1, status: 'ACTIVE' }
}

function du(id: number, nozzles: NozzleDetail[]): DUOption {
  return { id, pumpId: 3, duNumber: id, name: `DU ${id}`, status: 'ACTIVE', nozzles }
}

describe('DispenserTile', () => {
  it('shows the unit with its fuels and how many nozzles are free', () => {
    render(<DispenserTile du={du(1, [nozzle(21, 1, 'PETROL'), nozzle(22, 2, 'DIESEL'), nozzle(23, 3, 'DIESEL')])}
      busyNozzleOperators={new Map([[23, 'Sita']])} isSelected={false} onSelect={vi.fn()} />)

    const tile = screen.getByRole('button', { name: /DU 1/ })
    expect(within(tile).getByText('Petrol')).toBeInTheDocument()
    expect(within(tile).getByText('Diesel')).toBeInTheDocument()
    expect(tile).toHaveTextContent('2 free · 1 in use')
    expect(tile).toBeEnabled()
  })

  it('cannot be picked when every nozzle is in use', () => {
    render(<DispenserTile du={du(2, [nozzle(31, 1, 'CNG')])} busyNozzleOperators={new Map([[31, 'Asha']])} isSelected={false} onSelect={vi.fn()} />)

    const tile = screen.getByRole('button', { name: /DU 2/ })
    expect(within(tile).getByText('CNG')).toBeInTheDocument()
    expect(tile).toHaveTextContent('All nozzles in use')
    expect(tile).toBeDisabled()
  })
})

describe('NozzleTile', () => {
  it('shows the meter reading in the fuel\'s unit', () => {
    render(<NozzleTile nozzle={nozzle(22, 2, 'CNG', 50.5)} isBusy={false} busyOperatorName={undefined} isSelected={false} onToggle={vi.fn()} />)

    expect(screen.getByRole('button', { name: /#2/ })).toHaveTextContent('Meter 50.5 kg')
  })

  it('shows who has a busy nozzle and cannot be picked', () => {
    render(<NozzleTile nozzle={nozzle(23, 3, 'DIESEL')} isBusy busyOperatorName="Sita" isSelected={false} onToggle={vi.fn()} />)

    const tile = screen.getByRole('button', { name: /#3/ })
    expect(tile).toHaveTextContent('In use · Sita')
    expect(tile).toBeDisabled()
    expect(tile).toHaveAttribute('aria-pressed', 'false')
  })
})
