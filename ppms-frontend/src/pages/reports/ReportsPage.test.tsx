import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reportApi } from '../../api/reportApi'
import type { ProfitLossReport } from '../../api/reportApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import ReportsPage from './ReportsPage'

vi.mock('../../api/reportApi', () => ({
  reportApi: { getProfitLoss: vi.fn(), getExpensesReport: vi.fn().mockResolvedValue([]) },
}))
vi.mock('../../api/pumpApi', () => ({ pumpApi: { getMyPumps: vi.fn().mockResolvedValue([]) } }))

const PUMP_ID = 6

const reportWithOneLotAtTwoPrices: ProfitLossReport = {
  pumpId: PUMP_ID, from: '2026-01-01', to: '2026-01-31', totalShifts: 2,
  totalRevenue: 3000, totalCogs: 2700, grossProfit: 300,
  totalCashRevenue: 3000, totalUpiRevenue: 0, totalCardRevenue: 0, totalCreditRevenue: 0,
  byFuelType: [{
    fuelType: 'PETROL', revenue: 3000, cogs: 2700, grossProfit: 300, grossMarginPerLitre: 10,
    lotCostLines: [
      { lotId: 9, tankerReference: 'INV-9', deliveryDate: '2026-01-02', costPricePerUnit: 90, quantityConsumed: 20, lotCost: 1800 },
      { lotId: 9, tankerReference: 'INV-9', deliveryDate: '2026-01-02', costPricePerUnit: 91, quantityConsumed: 10, lotCost: 910 },
    ],
  }],
}

function renderReportsAt(path: string) {
  const router = createMemoryRouter([{ path: '/dashboard/reports', element: <ReportsPage /> }], { initialEntries: [path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('ReportsPage', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: PUMP_ID })
    vi.mocked(reportApi.getProfitLoss).mockResolvedValue(reportWithOneLotAtTwoPrices)
  })

  it('opens the tab and date range named in the address, as after a refresh', async () => {
    renderReportsAt('/dashboard/reports?tab=pl&from=2026-01-01&to=2026-01-31')

    await waitFor(() => expect(reportApi.getProfitLoss).toHaveBeenCalledWith(PUMP_ID, '2026-01-01', '2026-01-31'))
    expect(screen.getByRole('button', { name: 'Profit & Loss' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('From')).toHaveValue('2026-01-01')
  })

  it('keeps the chosen dates when switching to another tab', async () => {
    const user = userEvent.setup()
    const router = renderReportsAt('/dashboard/reports?tab=pl')

    const from = await screen.findByLabelText('From')
    await user.clear(from)
    await user.type(from, '2026-02-01')
    const to = screen.getByLabelText('To')
    await user.clear(to)
    await user.type(to, '2026-02-10')
    await user.click(screen.getByRole('button', { name: 'Generate' }))
    await user.click(screen.getByRole('button', { name: 'Expenses' }))

    await waitFor(() => expect(reportApi.getExpensesReport).toHaveBeenCalledWith(PUMP_ID, '2026-02-01', '2026-02-10'))
    expect(screen.getByLabelText('From')).toHaveValue('2026-02-01')
    expect(router.state.location.search).toBe('?tab=expenses&from=2026-02-01&to=2026-02-10')
  })

  it('lists every lot cost line, even when one lot was drawn at two prices', async () => {
    renderReportsAt('/dashboard/reports?tab=pl&from=2026-01-01&to=2026-01-31')

    expect(await screen.findByText('₹90.00')).toBeInTheDocument()
    expect(screen.getByText('₹91.00')).toBeInTheDocument()
  })
})
