import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shiftApi } from '../../api/shiftApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import type { Shift } from '../../types/shift'
import ShiftsPage from './ShiftsPage'

vi.mock('../../api/shiftApi', () => ({
  shiftApi: {
    getActiveShifts: vi.fn().mockResolvedValue([]),
    getShift: vi.fn(),
    getShiftHistoryPage: vi.fn(),
    getShiftHistory: vi.fn().mockResolvedValue([]),
  },
}))
vi.mock('../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn().mockResolvedValue([{ id: 4, name: 'Pump' }]) } }))
vi.mock('./history/HistoryRow', () => ({ HistoryRow: ({ shift }: { shift: Shift }) => <tr><td>History row {shift.id}</td></tr> }))

function renderAt(path: string) {
  const router = createMemoryRouter([{ path: '/dashboard/shifts', element: <ShiftsPage /> }], { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('ShiftsPage links from other pages', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 4 })
    vi.mocked(shiftApi.getShiftHistoryPage).mockResolvedValue({ content: [], page: { size: 10, number: 0, totalElements: 0, totalPages: 0 } })
  })

  it('opens the one shift named in the address', async () => {
    vi.mocked(shiftApi.getShift).mockResolvedValue({ id: 123 } as Shift)
    renderAt('/dashboard/shifts?shift=123')

    expect(await screen.findByText('History row 123')).toBeInTheDocument()
    expect(shiftApi.getShift).toHaveBeenCalledWith(4, 123)
    expect(screen.getByRole('button', { name: 'Show all shifts' })).toBeInTheDocument()
  })

  it('opens all shifts for a fuel and date range', async () => {
    renderAt('/dashboard/shifts?history=all&from=2026-10-01&to=2026-10-31&fuel=PETROL')

    await waitFor(() => expect(shiftApi.getShiftHistoryPage).toHaveBeenCalledWith(
      4, { from: '2026-10-01', to: '2026-10-31', fuelType: 'PETROL' }, 0, 10))
    expect(screen.getByRole('button', { name: 'All Shifts' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /Showing Petrol shifts only/ })).toBeInTheDocument()
  })

  it('opens the discrepancies tab filtered to short shifts', async () => {
    renderAt('/dashboard/shifts?history=discrepancies&from=2026-10-01&to=2026-10-31&type=SHORT')

    await waitFor(() => expect(shiftApi.getShiftHistory).toHaveBeenCalledWith(4, expect.objectContaining({
      from: '2026-10-01', to: '2026-10-31', discrepanciesOnly: true, discrepancyType: 'SHORT',
    })))
  })
})
