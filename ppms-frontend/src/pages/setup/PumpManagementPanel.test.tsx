import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import type { FuelPrice, PumpSummary } from '../../api/pumpApi'
import { useAuthStore } from '../../store/authStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { PumpManagementPanel } from './PumpManagementPanel'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCurrentPrices: vi.fn() } }))
vi.mock('../../api/userApi', () => ({ userApi: { getStaff: vi.fn().mockResolvedValue([]) } }))
vi.mock('../../api/shiftDefinitionApi', () => ({ shiftDefinitionApi: { getAll: vi.fn().mockResolvedValue([]) } }))

const pump: PumpSummary = {
  id: 3, name: 'Highway Pump', address: 'NH 48', maxDuCount: 4, ownerId: 1, createdAt: '2026-01-01T00:00:00Z',
  discrepancyEscalationThreshold: null, expenseApprovalThreshold: null, gstin: null, dus: [],
}

function petrolPriceSetOn(effectiveFrom: string): FuelPrice {
  return { id: 1, pumpId: 3, fuelType: 'PETROL', pricePerUnit: 102.5, effectiveFrom, setByUserId: 1 }
}

describe('PumpManagementPanel badges', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
  })

  it('marks fuel prices set on an earlier day as needing attention, like the overview does', async () => {
    vi.mocked(pumpApi.getCurrentPrices).mockResolvedValue([petrolPriceSetOn('2020-01-01T03:00:00Z')])
    renderWithProviders(<PumpManagementPanel pump={pump} onPumpUpdated={vi.fn()} />)

    const badge = await screen.findByText('P: ₹102.5 · not updated today')
    expect(badge).toHaveClass('text-warn')
  })

  it('shows today\'s fuel prices as complete', async () => {
    vi.mocked(pumpApi.getCurrentPrices).mockResolvedValue([petrolPriceSetOn(new Date().toISOString())])
    renderWithProviders(<PumpManagementPanel pump={pump} onPumpUpdated={vi.fn()} />)

    const badge = await screen.findByText('P: ₹102.5')
    expect(badge).toHaveClass('text-ok')
  })

  it('marks missing GST details as needing attention and filled ones as complete', async () => {
    vi.mocked(pumpApi.getCurrentPrices).mockResolvedValue([])
    const { unmount } = renderWithProviders(<PumpManagementPanel pump={pump} onPumpUpdated={vi.fn()} />)

    expect(screen.getByText('Not set')).toHaveClass('text-warn')
    expect(screen.getAllByText('Not configured').at(-1)).toHaveClass('text-warn')
    unmount()

    renderWithProviders(<PumpManagementPanel pump={{ ...pump, gstin: '27AAAAA0000A1Z5' }} onPumpUpdated={vi.fn()} />)
    expect(screen.getByText('GSTIN 27AAAAA0000A1Z5')).toHaveClass('text-ok')
  })
})
