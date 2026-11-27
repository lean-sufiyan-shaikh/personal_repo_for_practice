import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { superAdminApi, type OwnerSummary, type PumpSummary } from '../../api/superAdminApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { OwnerDemoPumpActions } from './OwnerDemoPumpActions'
import { PumpCard } from './PumpCard'

vi.mock('../../api/superAdminApi', () => ({ superAdminApi: { createDemoPump: vi.fn(), deleteDemoPump: vi.fn() } }))

const realPump: PumpSummary = {
  pumpId: 1, pumpName: 'Highway Pump', pumpAddress: 'NH 48', enabled: true, staffCount: 4, setupDoneCount: 6, setupRequiredCount: 6,
}
const demoPump: PumpSummary = { ...realPump, pumpId: 2, pumpName: 'Demo Pump', demo: true }

const owner = (pumps: PumpSummary[]): OwnerSummary => ({
  ownerId: 7, employeeId: 'OWN1', fullName: 'Asha Patil', phoneNumber: '9822000000', status: 'ACTIVE', createdAt: '', pumps,
})

describe('OwnerDemoPumpActions', () => {
  beforeEach(() => {
    vi.mocked(superAdminApi.createDemoPump).mockResolvedValue({ pumpId: 2, pumpName: 'Demo Pump' })
    vi.mocked(superAdminApi.deleteDemoPump).mockResolvedValue(undefined)
  })

  it('creates a demo pump for an owner without one', async () => {
    renderWithProviders(<OwnerDemoPumpActions owner={owner([realPump])} />)

    await userEvent.click(screen.getByRole('button', { name: 'Create demo pump' }))

    expect(superAdminApi.createDemoPump).toHaveBeenCalledWith(7)
  })

  it('deletes the owner demo pump after confirming', async () => {
    const user = userEvent.setup()
    renderWithProviders(<OwnerDemoPumpActions owner={owner([realPump, demoPump])} />)

    await user.click(screen.getByRole('button', { name: 'Delete demo pump' }))
    await user.click(screen.getByRole('button', { name: 'Yes' }))

    expect(superAdminApi.deleteDemoPump).toHaveBeenCalledWith(7)
  })
})

describe('PumpCard', () => {
  it('badges a demo pump', () => {
    const props = { isEditOpen: false, onToggleEdit: vi.fn(), onToggleEnabled: vi.fn(), isSaving: false, error: null, onSaveEdit: vi.fn() }
    const { unmount } = renderWithProviders(<PumpCard pump={demoPump} {...props} />)
    expect(screen.getByText('Demo')).toBeInTheDocument()
    unmount()

    renderWithProviders(<PumpCard pump={realPump} {...props} />)
    expect(screen.queryByText('Demo')).not.toBeInTheDocument()
  })
})
