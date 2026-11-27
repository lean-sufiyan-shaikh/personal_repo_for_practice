import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { monthCloseApi } from '../../api/monthCloseApi'
import type { MonthCloseDetail, MonthSummary } from '../../api/monthCloseApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../test/screenWidth'
import MonthClosePage from './MonthClosePage'

vi.mock('../../api/monthCloseApi', () => ({
  monthCloseApi: { getDetail: vi.fn(), getVersion: vi.fn(), close: vi.fn(), reopen: vi.fn() },
}))

const open: MonthCloseDetail = {
  month: '2026-10', fromDate: '2026-10-01', toDate: '2026-10-31', monthOver: true, status: null, currentVersion: null,
  days: [
    { date: '2026-10-01', statusCode: 'APPROVED', fixPath: '/dashboard/day-close?date=2026-10-01' },
    { date: '2026-10-02', statusCode: 'NOT_CLOSED', fixPath: '/dashboard/day-close?date=2026-10-02' },
  ],
  notReadyDayCount: 1, summary: null, versions: [],
}

const summary: MonthSummary = {
  month: '2026-10', fromDate: '2026-10-01', toDate: '2026-10-31', builtAt: '2026-11-02T10:00:00Z',
  businessDayCount: 2, approvedDayCount: 1, exceptions: [{ date: '2026-10-02', statusCode: 'NOT_CLOSED', reason: 'Pump shut' }],
  fuels: [{ fuelType: 'PETROL', litres: 1000, revenue: 100000, cost: 95000, margin: 5000 }],
  fuelLitres: 1000, fuelRevenue: 100000, fuelCost: 95000, fuelMargin: 5000, productRevenue: 0, productCost: 0, productMargin: 0,
  payments: { cash: 60000, upi: 40000, card: 0, fleetCard: 0, credit: 0, total: 100000 },
  grossProfit: 5000,
  costs: { expenses: 1200, payroll: 0, bankCharges: 0, chargebacks: 300, badDebt: 0, otherCosts: 0, total: 1500 },
  netProfit: 3500, credit: { given: 0, collected: 0, outstandingAtClose: 0 }, safeClosing: 8000, pettyCashClosing: 400,
  tanks: [{ tankId: 1, tankIdentifier: 'T1', fuelType: 'PETROL', lastDipDate: null, closingLitres: null, monthVarianceLitres: null }],
}

const closed: MonthCloseDetail = {
  ...open, status: 'CLOSED', currentVersion: 1, summary,
  versions: [{ version: 1, closedByName: 'Owner', closedAt: '2026-11-02T10:00:00Z', reopenedByName: null, reopenedAt: null, reopenReason: null }],
}

function signInAs(role: 'OWNER' | 'ADMIN') {
  useAuthStore.setState({ user: { userId: 1, fullName: role, role, assignedPumpId: 3 }, isAuthenticated: true })
}

describe('MonthClosePage', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    usePumpStore.setState({ selectedPumpId: 3 })
    vi.mocked(monthCloseApi.close).mockReset().mockResolvedValue(closed)
    vi.mocked(monthCloseApi.reopen).mockReset().mockResolvedValue({ ...closed, status: 'REOPENED' })
  })

  it('needs a reason for each day not approved before the month closes, then sends them as exceptions', async () => {
    signInAs('ADMIN')
    setScreenWidth(390)
    vi.mocked(monthCloseApi.getDetail).mockResolvedValue(open)
    const user = userEvent.setup()
    renderWithProviders(<MonthClosePage />, { route: '/dashboard/month-close?month=2026-10' })

    expect(await screen.findByText('1 of 2 days approved')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open day' })).toHaveAttribute('href', '/dashboard/day-close?date=2026-10-02')
    const close = screen.getByRole('button', { name: 'Close month' })
    expect(close).toBeDisabled()
    expect(screen.getByText('Give a reason for 1 day not approved.')).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: /Reason for/ }), 'Pump shut for repairs')
    await user.click(close)

    expect(monthCloseApi.close).toHaveBeenCalledWith(3, '2026-10', [{ date: '2026-10-02', reason: 'Pump shut for repairs' }])
  })

  it('shows the stored summary of a closed month and lets only the owner re-open it with a reason', async () => {
    signInAs('OWNER')
    vi.mocked(monthCloseApi.getDetail).mockResolvedValue(closed)
    const user = userEvent.setup()
    renderWithProviders(<MonthClosePage />, { route: '/dashboard/month-close?month=2026-10' })

    expect(await screen.findByText('Closed · version 1')).toBeInTheDocument()
    const costs = screen.getByLabelText('Costs and net profit')
    expect(within(costs).getByText('Chargebacks & refunds')).toBeInTheDocument()
    expect(within(costs).getByText('₹3,500.00')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Print' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Re-open month' }))
    const dialog = screen.getByRole('dialog', { name: 'Re-open 2026-10' })
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Late supplier bill')
    await user.click(within(dialog).getByRole('button', { name: 'Re-open month' }))

    expect(monthCloseApi.reopen).toHaveBeenCalledWith(3, '2026-10', 'Late supplier bill')
  })

  it('offers an admin no re-open', async () => {
    signInAs('ADMIN')
    vi.mocked(monthCloseApi.getDetail).mockResolvedValue(closed)
    renderWithProviders(<MonthClosePage />, { route: '/dashboard/month-close?month=2026-10' })

    expect(await screen.findByText('Closed · version 1')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Re-open month' })).not.toBeInTheDocument()
  })
})
