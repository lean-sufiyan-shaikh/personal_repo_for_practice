import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary } from '../../api/pumpApi'
import { useToastStore } from '../../store/toastStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { PumpSettingsContent } from './PumpSettingsContent'

vi.mock('../../api/pumpApi', () => ({ pumpApi: { updatePumpSettings: vi.fn() } }))

const pump = {
  id: 3, name: 'Pump 3', address: 'Pune', maxDuCount: 4, ownerId: 1, createdAt: '2026-01-01T00:00:00Z',
  discrepancyEscalationThreshold: 500, expenseApprovalThreshold: null, dus: [],
} as PumpSummary

describe('PumpSettingsContent', () => {
  beforeEach(() => {
    vi.mocked(pumpApi.updatePumpSettings).mockResolvedValue(pump)
    useToastStore.setState({ toasts: [] })
  })

  it('enables Save only after the limit changes, then saves it and confirms with a toast', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const save = screen.getByRole('button', { name: 'Save settings' })
    expect(save).toBeDisabled()

    const limit = screen.getByRole('spinbutton', { name: /Owner approval limit/ })
    await user.clear(limit)
    await user.type(limit, '750')
    expect(limit).toHaveAccessibleDescription(/above ₹750\.00/)
    expect(save).toBeEnabled()
    await user.click(save)

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { discrepancyEscalationThreshold: 750 })
    expect(useToastStore.getState().toasts.map((toast) => toast.message)).toEqual(['Settings saved'])
    expect(save).toBeDisabled()
  })

  it('saves the receipt and density tolerances, starting from the defaults', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const tolerance = screen.getByRole('spinbutton', { name: 'Short / over tolerance' })
    const density = screen.getByRole('spinbutton', { name: 'Density tolerance' })
    expect(tolerance).toHaveValue(0.2)
    expect(density).toHaveValue(3)
    expect(tolerance).toHaveAccessibleDescription(/more than 0\.20% short or over/)

    await user.clear(tolerance)
    await user.type(tolerance, '7')
    expect(tolerance).toHaveAccessibleDescription(/at most 5%/)
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()

    await user.clear(tolerance)
    await user.type(tolerance, '0.3')
    await user.clear(density)
    await user.type(density, '2.5')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { receiptTolerancePercent: 0.3, densityTolerance: 2.5 })
  })

  it('saves the safe count tolerance, starting from ₹10 and allowing ₹0', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const tolerance = screen.getByRole('spinbutton', { name: 'Safe count tolerance' })
    expect(tolerance).toHaveValue(10)
    expect(tolerance).toHaveAccessibleDescription(/more than ₹10.00 short or over/)

    await user.clear(tolerance)
    await user.type(tolerance, '10001')
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()

    await user.clear(tolerance)
    await user.type(tolerance, '0')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { safeCountTolerance: 0 })
  })
})
