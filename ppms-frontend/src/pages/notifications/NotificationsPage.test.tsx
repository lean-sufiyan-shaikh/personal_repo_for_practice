import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { notificationApi } from '../../api/notificationApi'
import type { Notification } from '../../api/notificationApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import NotificationsPage from './NotificationsPage'

vi.mock('../../api/notificationApi', () => ({
  notificationApi: { getHistory: vi.fn(), markRead: vi.fn() },
}))

const unread: Notification = {
  id: 1, pumpId: 4, type: 'SETTLEMENT_REMINDER', title: 'UPI Settlement Reminder', message: 'Record UPI',
  createdAt: '2026-11-23T10:00:00Z', read: false, target: { route: '/dashboard/settlements', params: {} },
}
const read: Notification = { ...unread, id: 2, type: 'LOW_STOCK', title: 'Low Tank Stock', read: true,
  target: { route: '/dashboard/inventory', params: {} } }

function page(content: Notification[]) {
  return { content, page: 0, pageSize: 20, totalElements: content.length, totalPages: 1, hasNext: false, hasPrevious: false }
}

describe('NotificationsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 4 })
    vi.mocked(notificationApi.getHistory).mockResolvedValue(page([unread, read]))
    vi.mocked(notificationApi.markRead).mockResolvedValue(undefined)
  })

  it('lists read and unread notifications with their state in words', async () => {
    renderWithProviders(<NotificationsPage />)

    expect(await screen.findByText('UPI Settlement Reminder')).toBeInTheDocument()
    expect(screen.getByText('Unread')).toBeInTheDocument()
    expect(screen.getByText('Read')).toBeInTheDocument()
    expect(notificationApi.getHistory).toHaveBeenCalledWith(4, 0, 20, undefined)
  })

  it('filters by type', async () => {
    const user = userEvent.setup()
    renderWithProviders(<NotificationsPage />)
    await screen.findByText('UPI Settlement Reminder')

    await user.selectOptions(screen.getByLabelText('Type'), 'LOW_STOCK')

    await waitFor(() => expect(notificationApi.getHistory).toHaveBeenLastCalledWith(4, 0, 20, 'LOW_STOCK'))
  })

  it('marks an unread notification read when it is opened', async () => {
    const user = userEvent.setup()
    renderWithProviders(<NotificationsPage />)

    await user.click(await screen.findByRole('link', { name: /UPI Settlement Reminder/ }))

    await waitFor(() => expect(notificationApi.markRead).toHaveBeenCalledWith(4, [1]))
  })
})
