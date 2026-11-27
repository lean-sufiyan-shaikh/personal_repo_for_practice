import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { dipApi } from '../../../api/dipApi'
import type { NozzleReadingAdjustment } from '../../../api/dipApi'
import { pumpApi } from '../../../api/pumpApi'
import { useAuthStore } from '../../../store/authStore'
import type { UserRole } from '../../../types/auth'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { NozzleDetail } from '../../../types/shift'
import { AdjustReadingPanel } from './AdjustReadingPanel'

vi.mock('../../../api/dipApi', () => ({ dipApi: { recordAdjustment: vi.fn(), getAdjustments: vi.fn() } }))
vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getCurrentPrices: vi.fn() } }))

const nozzle: NozzleDetail = {
  id: 21, nozzleNumber: 2, fuelType: 'PETROL', lastReading: 1000, maxMeterValue: 99_999_999.999, tankId: 4, status: 'ACTIVE',
}

const pending: NozzleReadingAdjustment = {
  id: 9, pumpId: 6, nozzleId: 21, adjustmentType: 'CUSTOM_READING', fuelType: 'PETROL', previousReading: 1000,
  newReading: 1012.5, reason: 'Meter ran during power cut', recordedByUserId: 2, createdAt: '2026-11-26T08:00:00Z',
  status: 'PENDING_APPROVAL', unbilledLitres: 12.5, unbilledValue: 1250, pricePerLitre: 100,
  reviewedByUserId: null, reviewedAt: null, reviewReason: null,
}

const figure = (label: string) => screen.getByText(label, { selector: 'dt' }).nextElementSibling?.textContent

function signInAs(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: 6 }, isAuthenticated: true })
}

describe('AdjustReadingPanel', () => {
  beforeEach(() => {
    signInAs('OWNER')
    vi.mocked(dipApi.recordAdjustment).mockReset()
    vi.mocked(dipApi.recordAdjustment).mockResolvedValue({ ...pending, status: 'APPLIED' })
    vi.mocked(dipApi.getAdjustments).mockResolvedValue([])
    vi.mocked(pumpApi.getCurrentPrices).mockResolvedValue([
      { id: 1, pumpId: 6, fuelType: 'PETROL', pricePerUnit: 100, effectiveFrom: '2026-11-01', setByUserId: 1 },
    ])
  })

  it('shows the difference from the current reading as the new reading is typed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    expect(figure('Current reading')).toBe('1,000 L')
    expect(figure('Difference')).toBe('—')

    await user.type(screen.getByRole('spinbutton', { name: 'New reading' }), '1012.5')

    expect(figure('Difference')).toBe('+12.5 L')
  })

  it('shows the whole reading going when the meter is reset to zero', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: /Reset to zero/ }))

    expect(figure('Difference')).toBe('−1,000 L')
    expect(screen.getByRole('button', { name: 'Reset to zero' })).toHaveClass('ui-btn-danger')
  })

  it('tells an owner that a forward move is taken from tank stock as unbilled litres', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    await user.type(screen.getByRole('spinbutton', { name: 'New reading' }), '1012.5')

    expect(await screen.findByText(/Forward by 12.5 L ≈ ₹1,250.00 at the current price\. These unbilled litres will be taken from tank stock/))
      .toBeInTheDocument()
  })

  it('lets a manager only ask for a forward adjustment, which waits for approval', async () => {
    signInAs('MANAGER')
    vi.mocked(dipApi.recordAdjustment).mockResolvedValue(pending)
    const user = userEvent.setup()
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    expect(screen.queryByRole('button', { name: /Reset to zero/ })).not.toBeInTheDocument()
    await user.type(screen.getByRole('spinbutton', { name: 'New reading' }), '1012.5')
    expect(await screen.findByText(/needs Owner or Admin approval before the reading changes/)).toBeInTheDocument()

    await user.type(screen.getByRole('textbox'), 'Meter ran during power cut')
    await user.click(screen.getByRole('button', { name: 'Ask for approval' }))

    expect(dipApi.recordAdjustment).toHaveBeenCalledWith(6, 21, 'CUSTOM_READING', 'Meter ran during power cut', 1012.5)
  })

  it('refuses a lower reading from a manager without calling the server', async () => {
    signInAs('MANAGER')
    const user = userEvent.setup()
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    await user.type(screen.getByRole('spinbutton', { name: 'New reading' }), '990')
    await user.type(screen.getByRole('textbox'), 'Typo')
    await user.click(screen.getByRole('button', { name: 'Ask for approval' }))

    expect(screen.getByRole('alert')).toHaveTextContent('Only an Owner or Admin can move a reading back')
    expect(dipApi.recordAdjustment).not.toHaveBeenCalled()
  })

  it('lists recent adjustments with the one waiting for approval marked', async () => {
    vi.mocked(dipApi.getAdjustments).mockResolvedValue([pending])
    renderWithProviders(<AdjustReadingPanel pumpId={6} nozzle={nozzle} onClose={vi.fn()} />)

    expect(await screen.findByText('Pending approval')).toBeInTheDocument()
    expect(screen.getByText(/12.5 L unbilled \(₹1,250.00\)/)).toBeInTheDocument()
  })
})
