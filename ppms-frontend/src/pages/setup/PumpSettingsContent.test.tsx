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
    await user.click(limit)
    await user.paste('750')
    expect(limit).toHaveAccessibleDescription(/above ₹750\.00/)
    expect(save).toBeEnabled()
    await user.click(save)

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { discrepancyEscalationThreshold: 750 })
    expect(useToastStore.getState().toasts.map((toast) => toast.message)).toEqual(['Settings saved'])
    expect(save).toBeDisabled()
  })

  it('treats a limit of ₹0 as sending every discrepancy to the owner and refuses a blank limit', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const limit = screen.getByRole('spinbutton', { name: /Owner approval limit/ })
    await user.clear(limit)
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()
    await user.type(limit, '0')

    expect(limit).toHaveAccessibleDescription(/every shortage or overage needs Owner or Admin/)
  })

  it('saves the rounding allowance and the early start grace, starting from ₹1 and 30 minutes', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const rounding = screen.getByRole('spinbutton', { name: 'Rounding allowance' })
    const grace = screen.getByRole('spinbutton', { name: 'Early start grace' })
    expect(rounding).toHaveValue(1)
    expect(grace).toHaveValue(30)
    expect(grace).toHaveAccessibleDescription(/Up to 30 minutes before a window starts/)

    await user.clear(grace)
    await user.click(grace)
    await user.paste('121')
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()
    await user.clear(grace)
    await user.click(grace)
    await user.paste('15')
    await user.clear(rounding)
    await user.type(rounding, '2')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { roundingTolerance: 2, earlyStartGraceMinutes: 15 })
  })

  it('saves the nozzle test measure and the test litres needing a note, starting from 5 L and 10 L', async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const measure = screen.getByRole('spinbutton', { name: 'Test measure' })
    const limit = screen.getByRole('spinbutton', { name: 'Test litres needing a note' })
    expect(measure).toHaveValue(5)
    expect(limit).toHaveValue(10)
    expect(limit).toHaveAccessibleDescription(/More than 10\.00 L returned on one nozzle/)

    await user.clear(measure)
    await user.click(measure)
    await user.paste('101')
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()
    await user.clear(measure)
    await user.click(measure)
    await user.paste('10')
    await user.clear(limit)
    await user.click(limit)
    await user.paste('20')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { testVolumeLitres: 10, maxTestLitresPerNozzle: 20 })
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
    await user.click(tolerance)
    await user.paste('0.3')
    await user.clear(density)
    await user.click(density)
    await user.paste('2.5')
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
    await user.click(tolerance)
    await user.paste('10001')
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()

    await user.clear(tolerance)
    await user.type(tolerance, '0')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { safeCountTolerance: 0 })
  })

  it("saves the cut-off for yesterday's safe count, starting from 10:00", async () => {
    const user = userEvent.setup()
    renderWithProviders(<PumpSettingsContent pump={pump} onUpdated={vi.fn()} />)

    const cutoff = screen.getByLabelText("Cut-off for yesterday's count")
    expect(cutoff).toHaveValue('10:00')
    expect(cutoff).toHaveAccessibleDescription(/Until 10:00 a manager may still count the safe for yesterday/)

    await user.clear(cutoff)
    expect(screen.getByRole('button', { name: 'Save settings' })).toBeDisabled()
    await user.click(cutoff)
    await user.paste('08:30')
    await user.click(screen.getByRole('button', { name: 'Save settings' }))

    expect(pumpApi.updatePumpSettings).toHaveBeenCalledWith(3, { safeCountCutoffTime: '08:30' })
  })
})
