import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { notificationApi } from '../api/notificationApi'
import type { Notification } from '../api/notificationApi'
import { useAuthStore } from '../store/authStore'
import NotificationBell from './NotificationBell'

vi.mock('../api/notificationApi', () => ({
  notificationApi: { getNotifications: vi.fn(), markRead: vi.fn() },
}))

function notification(overrides: Partial<Notification>): Notification {
  return {
    id: 1, pumpId: 4, type: 'ZERO_SALE_SHIFT', title: 'Zero-Sale Shift Detected',
    message: 'Shift #123 recorded ₹0 in sales.', createdAt: '2026-11-23T10:00:00Z', read: false,
    target: { route: '/dashboard/shifts', params: { shift: '123' } },
    ...overrides,
  }
}

function renderBell() {
  const router = createMemoryRouter([
    { path: '/dashboard', element: <NotificationBell pumpId={4} /> },
    { path: '/dashboard/shifts', element: <p>Shifts page</p> },
    { path: '/dashboard/notifications', element: <p>All notifications page</p> },
  ], { initialEntries: ['/dashboard'] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-11-23T12:00:00Z'))
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    vi.mocked(notificationApi.markRead).mockResolvedValue(undefined)
  })

  afterEach(() => vi.useRealTimers())

  it('opens the shift a notification is about and marks only that notification read', async () => {
    vi.mocked(notificationApi.getNotifications).mockResolvedValue({
      notifications: [notification({ id: 7 }), notification({ id: 8, type: 'LOW_STOCK', title: 'Low Tank Stock', message: 'T1 is low',
        target: { route: '/dashboard/inventory', params: {} } })],
      unreadCount: 2,
    })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const router = renderBell()

    await user.click(await screen.findByRole('button', { name: 'Notifications, 2 unread' }))
    expect(screen.getAllByText('2h ago')[0]).toHaveAttribute('dateTime', '2026-11-23T10:00:00Z')
    await user.click(screen.getByRole('link', { name: /Zero-Sale Shift Detected/ }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard/shifts'))
    expect(router.state.location.search).toBe('?shift=123')
    expect(notificationApi.markRead).toHaveBeenCalledWith(4, [7])
  })

  it('shows alerts for pages the role cannot open without a link', async () => {
    useAuthStore.setState({ user: { userId: 2, fullName: 'Accountant', role: 'ACCOUNTANT', assignedPumpId: 4 }, isAuthenticated: true })
    vi.mocked(notificationApi.getNotifications).mockResolvedValue({
      notifications: [notification({ id: 9, type: 'LOW_STOCK', title: 'Low Tank Stock', message: 'T1 is low',
        target: { route: '/dashboard/inventory', params: {} } })],
      unreadCount: 1,
    })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderBell()

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    expect(screen.getByText('Low Tank Stock')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Low Tank Stock/ })).not.toBeInTheDocument()
  })

  it('keeps Mark all read and links to the full history', async () => {
    vi.mocked(notificationApi.getNotifications).mockResolvedValue({ notifications: [notification({ id: 3 })], unreadCount: 1 })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    const router = renderBell()

    await user.click(await screen.findByRole('button', { name: 'Notifications, 1 unread' }))
    await user.click(screen.getByRole('button', { name: 'Mark all read' }))
    expect(notificationApi.markRead).toHaveBeenCalledWith(4, [3])

    await user.click(screen.getByRole('link', { name: 'View all notifications' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard/notifications'))
  })
})
