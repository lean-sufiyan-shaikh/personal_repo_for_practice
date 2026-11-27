import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { demoPumpApi } from '../../../api/demoPumpApi'
import { pumpApi, type PumpSummary } from '../../../api/pumpApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { DemoPumpPanel } from './DemoPumpPanel'

vi.mock('../../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn() } }))
vi.mock('../../../api/demoPumpApi', () => ({ demoPumpApi: { create: vi.fn(), delete: vi.fn() } }))

const pump = (id: number, name: string, demo: boolean): PumpSummary => ({
  id, name, address: 'NH 48', maxDuCount: 4, ownerId: 1, createdAt: '', discrepancyEscalationThreshold: null,
  expenseApprovalThreshold: null, dus: [], demo,
})

describe('DemoPumpPanel', () => {
  beforeEach(() => {
    vi.mocked(demoPumpApi.create).mockResolvedValue({ pumpId: 9, pumpName: 'Demo Pump' })
    vi.mocked(demoPumpApi.delete).mockResolvedValue(undefined)
  })

  it('creates a demo pump when the owner has none', async () => {
    const user = userEvent.setup()
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([pump(1, 'Highway Pump', false)])
    renderWithProviders(<DemoPumpPanel />)

    await user.click(await screen.findByRole('button', { name: 'Create demo pump' }))

    expect(demoPumpApi.create).toHaveBeenCalled()
    expect(await screen.findByText('Demo pump created. Pick it from the pump list to explore.')).toBeInTheDocument()
  })

  it('deletes the existing demo pump only after the owner confirms', async () => {
    const user = userEvent.setup()
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([pump(1, 'Highway Pump', false), pump(9, 'Demo Pump', true)])
    renderWithProviders(<DemoPumpPanel />)

    expect(await screen.findByText('Your demo pump: Demo Pump')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create demo pump' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete demo pump' }))
    expect(screen.getByText('Delete the demo pump and all its sample data?')).toBeInTheDocument()
    expect(demoPumpApi.delete).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Yes' }))

    expect(demoPumpApi.delete).toHaveBeenCalled()
    expect(await screen.findByText('Demo pump deleted.')).toBeInTheDocument()
  })

  it('shows why creating failed', async () => {
    const user = userEvent.setup()
    vi.mocked(pumpApi.getMyPumps).mockResolvedValue([])
    vi.mocked(demoPumpApi.create).mockRejectedValue(new Error('offline'))
    renderWithProviders(<DemoPumpPanel />)

    await user.click(await screen.findByRole('button', { name: 'Create demo pump' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
