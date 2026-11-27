import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { staffAdvanceApi } from '../../../api/staffAdvanceApi'
import type { StaffAdvance } from '../../../api/staffAdvanceApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { StaffAdvancesTab } from './StaffAdvancesTab'

vi.mock('../../../api/staffAdvanceApi', () => ({ staffAdvanceApi: { list: vi.fn(), give: vi.fn(), void: vi.fn() } }))
vi.mock('../../../api/userApi', () => ({ userApi: { getStaff: vi.fn().mockResolvedValue([]) } }))

const advance = (overrides: Partial<StaffAdvance>): StaffAdvance => ({
  id: 1, staffUserId: 7, staffName: 'Asha', amount: 1500, advanceDate: '2026-11-20', note: null, recovered: 0,
  outstanding: 1500, status: 'ACTIVE', givenByName: 'Mgr', voidReason: null, voidedAt: null, createdAt: '2026-11-20T05:00:00Z',
  ...overrides,
})

describe('StaffAdvancesTab', () => {
  beforeEach(() => {
    vi.mocked(staffAdvanceApi.list).mockResolvedValue({
      advances: [advance({ id: 1 }), advance({ id: 2, staffName: 'Bala', staffUserId: 8, recovered: 800, outstanding: 700 })],
      outstandingByStaff: [{ staffUserId: 7, staffName: 'Asha', outstanding: 1500 }, { staffUserId: 8, staffName: 'Bala', outstanding: 700 }],
    })
    vi.mocked(staffAdvanceApi.void).mockResolvedValue(advance({ status: 'VOIDED', outstanding: 0 }))
  })

  it('shows what each person still owes and offers void only on an advance payroll has not touched', async () => {
    renderWithProviders(<StaffAdvancesTab pumpId={3} />)

    const owed = await screen.findByRole('region', { name: 'Still to recover' })
    expect(within(owed).getByText('Asha')).toBeInTheDocument()
    expect(owed).toHaveTextContent('₹700.00')
    expect(screen.getAllByRole('button', { name: 'Void' })).toHaveLength(1)
  })

  it('voids an advance with a reason', async () => {
    const user = userEvent.setup()
    renderWithProviders(<StaffAdvancesTab pumpId={3} />)

    await user.click(await screen.findByRole('button', { name: 'Void' }))
    const dialog = screen.getByRole('dialog', { name: 'Void advance' })
    await user.type(within(dialog).getByRole('textbox'), 'Given by mistake')
    await user.click(within(dialog).getByRole('button', { name: 'Void' }))

    expect(staffAdvanceApi.void).toHaveBeenCalledWith(3, 1, 'Given by mistake')
  })
})
