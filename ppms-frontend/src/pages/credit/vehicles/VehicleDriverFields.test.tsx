import { useState } from 'react'
import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { vehicleApi } from '../../../api/vehicleApi'
import type { ClientVehicle, ClientVehicles } from '../../../api/vehicleApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { VehicleDriverFields } from './VehicleDriverFields'
import { EMPTY_VEHICLE_ENTRY } from './vehicleEntry'
import type { VehicleEntryValue } from './vehicleEntry'

vi.mock('../../../api/vehicleApi', () => ({ vehicleApi: { getVehiclePicker: vi.fn() } }))

const dieselTruck: ClientVehicle = {
  id: 7, clientId: 40, clientName: 'Fleet Co', registration: 'MH12AB1234', allowedFuelTypes: ['DIESEL'],
  monthlyLitreLimit: null, monthlyAmountLimit: 5000, defaultDriverName: null, defaultDriverPhone: null,
  active: true, litresThisMonth: 0, amountThisMonth: 4000, litresLeft: null, amountLeft: 1000,
}

function picker(vehicleCheck: ClientVehicles['vehicleCheck'], vehicles: ClientVehicle[]): ClientVehicles {
  return { vehicleCheck, checkClientId: 40, checkClientName: 'Fleet Co', vehicles }
}

function Harness({ fuelType, amount, onChange }: { fuelType: string | null; amount: number; onChange?: (value: VehicleEntryValue) => void }) {
  const [value, setValue] = useState<VehicleEntryValue>(EMPTY_VEHICLE_ENTRY)
  return (
    <VehicleDriverFields pumpId={3} clientId={40} value={value} fuelType={fuelType} amount={amount} litres={null}
      onChange={(next) => { setValue(next); onChange?.(next) }} />
  )
}

describe('VehicleDriverFields', () => {
  beforeEach(() => {
    vi.mocked(vehicleApi.getVehiclePicker).mockReset()
  })

  it('on a BLOCK account offers only the listed vehicles and says a wrong fuel will be refused', async () => {
    vi.mocked(vehicleApi.getVehiclePicker).mockResolvedValue(picker('BLOCK', [dieselTruck]))
    renderWithProviders(<Harness fuelType="PETROL" amount={500} />)

    expect(await screen.findByRole('alert')).toHaveTextContent("Pick one of the client's listed vehicles.")
    expect(screen.queryByRole('textbox', { name: /Vehicle/ })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Vehicle/ }))
    fireEvent.click(await screen.findByRole('option', { name: 'MH12AB1234' }))

    expect(screen.getByRole('alert')).toHaveTextContent('MH12AB1234 may not take Petrol, so the entry will be refused.')
  })

  it('on a BLOCK account without listed vehicles says a manager must add one', async () => {
    vi.mocked(vehicleApi.getVehiclePicker).mockResolvedValue(picker('BLOCK', []))
    renderWithProviders(<Harness fuelType="DIESEL" amount={500} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('none is listed')
  })

  it('on a WARN account lets a registration be typed and says an unknown vehicle or a sale past the limit is flagged', async () => {
    vi.mocked(vehicleApi.getVehiclePicker).mockResolvedValue(picker('WARN', [dieselTruck]))
    const changes = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness fuelType={null} amount={1500} onChange={changes} />)

    const vehicle = await screen.findByRole('combobox', { name: 'Vehicle (optional)' })
    await user.type(vehicle, 'KA01ZZ9999')
    expect(screen.getByRole('alert')).toHaveTextContent('flagged as Unknown vehicle')
    expect(changes).toHaveBeenLastCalledWith(expect.objectContaining({ vehicleId: null, registration: 'KA01ZZ9999' }))

    await user.clear(vehicle)
    await user.type(vehicle, 'mh12-ab-1234')
    expect(changes).toHaveBeenLastCalledWith(expect.objectContaining({ vehicleId: 7, registration: 'MH12AB1234' }))
    expect(screen.getByText('Diesel · Left this month: ₹1,000.00')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('more than the ₹1,000.00 left for MH12AB1234 this month. The entry will be flagged.')
  })

  it('on an OFF account says nothing about the vehicle and keeps only digits in the driver phone', async () => {
    vi.mocked(vehicleApi.getVehiclePicker).mockResolvedValue(picker('OFF', []))
    const user = userEvent.setup()
    renderWithProviders(<Harness fuelType="PETROL" amount={500} />)

    await user.type(await screen.findByRole('combobox', { name: 'Vehicle (optional)' }), 'ANY TRUCK')
    await user.type(screen.getByLabelText('Driver phone (optional)'), '98-765 43210')

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Driver phone (optional)')).toHaveValue('9876543210')
  })
})
