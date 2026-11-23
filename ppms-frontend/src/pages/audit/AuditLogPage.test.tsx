import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { auditApi } from '../../api/auditApi'
import type { AuditLog } from '../../api/auditApi'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import AuditLogPage from './AuditLogPage'

vi.mock('../../api/auditApi', () => ({ auditApi: { getAuditLogs: vi.fn(), getActors: vi.fn() } }))

const shiftClosed: AuditLog = {
  id: 1, pumpId: 4, action: 'SHIFT_CLOSED', entityType: 'Shift', entityId: '123', description: 'Closed shift',
  actorId: 6, actorName: 'Ravi', createdAt: '2026-11-23T10:00:00Z',
}

describe('AuditLogPage', () => {
  beforeEach(() => {
    usePumpStore.setState({ selectedPumpId: 4 })
    vi.mocked(auditApi.getAuditLogs).mockResolvedValue({
      content: [shiftClosed], page: { size: 10, number: 0, totalElements: 1, totalPages: 1 },
    })
    vi.mocked(auditApi.getActors).mockResolvedValue([{ actorId: 6, actorName: 'Ravi' }])
  })

  it('hides sign-in events until asked to show them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AuditLogPage />)

    await screen.findByText('Closed shift')
    expect(auditApi.getAuditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ hideSignIns: true }))

    await user.click(screen.getByLabelText('Show sign-in events'))
    await waitFor(() => expect(auditApi.getAuditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ hideSignIns: false })))
  })

  it('filters by action and by user', async () => {
    const user = userEvent.setup()
    renderWithProviders(<AuditLogPage />)
    await screen.findByText('Closed shift')

    await user.selectOptions(screen.getByLabelText('Action'), 'SHIFT_CLOSED')
    await waitFor(() => expect(auditApi.getAuditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ action: 'SHIFT_CLOSED' })))

    await user.selectOptions(await screen.findByLabelText('User'), 'Ravi')
    await waitFor(() => expect(auditApi.getAuditLogs).toHaveBeenLastCalledWith(expect.objectContaining({ actorId: 6 })))
  })

  it('links the entity of an event to its page', async () => {
    renderWithProviders(<AuditLogPage />)
    expect(await screen.findByRole('link', { name: 'Shift #123' })).toHaveAttribute('href', '/dashboard/shifts?shift=123')
  })

  it('stacks the filters in one column on a phone and spreads them out on wider screens', async () => {
    renderWithProviders(<AuditLogPage />)
    const grid = (await screen.findByLabelText('Action')).closest('.grid')
    expect(grid).toHaveClass('grid-cols-1', 'sm:grid-cols-2', 'lg:grid-cols-4')
  })
})
