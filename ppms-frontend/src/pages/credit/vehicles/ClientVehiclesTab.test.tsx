import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { vehicleApi } from '../../../api/vehicleApi'
import type { ClientVehicle, ClientVehicles } from '../../../api/vehicleApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { ClientVehiclesTab } from './ClientVehiclesTab'

vi.mock('../../../api/vehicleApi', () => ({
  vehicleApi: {
    getVehicles: vi.fn(), addVehicle: vi.fn(), updateVehicle: vi.fn(), setVehicleActive: vi.fn(), setVehicleCheck: vi.fn(),
  },
}))

const truck: ClientVehicle = {
  id: 7, clientId: 40, clientName: 'Fleet Co', registration: 'MH12AB1234', allowedFuelTypes: ['DIESEL'],
  monthlyLitreLimit: 500, monthlyAmountLimit: null, defaultDriverName: 'Suresh', defaultDriverPhone: '9876543210',
  active: true, litresThisMonth: 120.5, amountThisMonth: 11000, litresLeft: 379.5, amountLeft: null,
}

function vehicles(overrides: Partial<ClientVehicles> = {}): ClientVehicles {
  return { vehicleCheck: 'OFF', checkClientId: 40, checkClientName: 'Fleet Co', vehicles: [truck], ...overrides }
}

describe('ClientVehiclesTab', () => {
  beforeEach(() => {
    vi.mocked(vehicleApi.getVehicles).mockReset().mockResolvedValue(vehicles())
  })

  it('lists each vehicle with its fuels, this month’s use against its limit and its driver', async () => {
    renderWithProviders(<ClientVehiclesTab pumpId={3} clientId={40} />)

    expect(await screen.findByText('MH12AB1234')).toBeInTheDocument()
    expect(screen.getByText('Diesel')).toBeInTheDocument()
    expect(screen.getByText('This month: 120.50 L · ₹11,000.00')).toBeInTheDocument()
    expect(screen.getByText('379.50 L left of 500.00 L')).toBeInTheDocument()
    expect(screen.getByText(/Driver: Suresh/)).toBeInTheDocument()
  })

  it('adds a vehicle with its registration normalised, the fuels ticked and the limits typed', async () => {
    vi.mocked(vehicleApi.addVehicle).mockResolvedValue({ ...truck, id: 8, registration: 'KA01ZZ9999' })
    const user = userEvent.setup()
    renderWithProviders(<ClientVehiclesTab pumpId={3} clientId={40} />)

    await user.click(await screen.findByRole('button', { name: 'Add vehicle' }))
    await user.type(screen.getByLabelText('Registration number'), 'ka-01 zz 9999')
    await user.click(screen.getByRole('checkbox', { name: 'Petrol' }))
    await user.type(screen.getByLabelText('Monthly amount limit (₹) (optional)'), '15000')
    await user.click(screen.getByRole('button', { name: 'Save vehicle' }))

    expect(vehicleApi.addVehicle).toHaveBeenCalledWith(3, 40, {
      registration: 'KA01ZZ9999', allowedFuelTypes: ['PETROL'], monthlyLitreLimit: null, monthlyAmountLimit: 15000,
      defaultDriverName: null, defaultDriverPhone: null,
    })
  })

  it('shows why a save was refused', async () => {
    vi.mocked(vehicleApi.addVehicle).mockRejectedValue({
      isAxiosError: true,
      response: { status: 400, data: { code: 'VEHICLE_REGISTRATION_TAKEN', params: { registration: 'MH12AB1234' },
        message: 'Vehicle MH12AB1234 is already listed as an active vehicle at this pump.' } },
    })
    const user = userEvent.setup()
    renderWithProviders(<ClientVehiclesTab pumpId={3} clientId={40} />)

    await user.click(await screen.findByRole('button', { name: 'Add vehicle' }))
    await user.type(screen.getByLabelText('Registration number'), 'MH12AB1234')
    await user.click(screen.getByRole('button', { name: 'Save vehicle' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('already listed as an active vehicle')
  })

  it('edits and deactivates a vehicle and sets the account’s check', async () => {
    vi.mocked(vehicleApi.updateVehicle).mockResolvedValue(truck)
    vi.mocked(vehicleApi.setVehicleActive).mockResolvedValue({ ...truck, active: false })
    vi.mocked(vehicleApi.setVehicleCheck).mockResolvedValue(vehicles({ vehicleCheck: 'BLOCK' }))
    const user = userEvent.setup()
    renderWithProviders(<ClientVehiclesTab pumpId={3} clientId={40} />)

    await user.click(await screen.findByRole('button', { name: 'Edit vehicle MH12AB1234' }))
    const driver = screen.getByLabelText('Usual driver (optional)')
    await user.clear(driver)
    await user.type(driver, 'Ramesh')
    await user.click(screen.getByRole('button', { name: 'Save vehicle' }))
    expect(vehicleApi.updateVehicle).toHaveBeenCalledWith(3, 40, 7, expect.objectContaining({
      registration: 'MH12AB1234', allowedFuelTypes: ['DIESEL'], monthlyLitreLimit: 500, defaultDriverName: 'Ramesh',
    }))

    await user.click(screen.getByRole('button', { name: 'Deactivate vehicle MH12AB1234' }))
    expect(vehicleApi.setVehicleActive).toHaveBeenCalledWith(3, 40, 7, false)

    await user.click(within(screen.getByRole('group', { name: 'Vehicle check' })).getByRole('radio', { name: /Block/ }))
    expect(vehicleApi.setVehicleCheck).toHaveBeenCalledWith(3, 40, 'BLOCK')
  })

  it('tells a sub-account its check is set on the parent', async () => {
    vi.mocked(vehicleApi.getVehicles).mockResolvedValue(vehicles({ vehicleCheck: 'WARN', checkClientId: 1, checkClientName: 'Parent Co' }))
    renderWithProviders(<ClientVehiclesTab pumpId={3} clientId={40} />)

    expect(await screen.findByText('The vehicle check is set on the parent account Parent Co: Warn.')).toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })
})
