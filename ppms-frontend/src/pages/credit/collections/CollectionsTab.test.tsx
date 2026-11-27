import { screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import type { CollectionDashboard } from '../../../api/collectionApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { resetScreenWidthAfterEach, setScreenWidth } from '../../../test/screenWidth'
import { CollectionsTab } from './CollectionsTab'

vi.mock('../../../api/collectionApi', () => ({
  collectionApi: { getDashboard: vi.fn(), recordReminder: vi.fn() },
}))

const dashboard: CollectionDashboard = {
  totalOutstanding: 20800,
  totalOverdue: 18000,
  overdueClientCount: 2,
  ageBuckets: { upTo30Days: 2800, days31To60: 10000, days61To90: 8000, over90Days: 0 },
  collectedThisMonth: 4200,
  promisesDueToday: [{
    id: 5, clientId: 41, clientName: 'Bharat Logistics', amount: 10000, promisedDate: '2026-11-27', recordedOn: '2026-11-20',
    note: null, status: 'OPEN', paidAmount: null, recordedByName: 'Manager', recordedAt: '2026-11-20T05:00:00Z', resolvedAt: null,
  }],
  missedPromises: [{
    id: 4, clientId: 42, clientName: 'City Cabs', amount: 3000, promisedDate: '2026-11-20', recordedOn: '2026-11-15',
    note: null, status: 'MISSED', paidAmount: 500, recordedByName: 'Manager', recordedAt: '2026-11-15T05:00:00Z', resolvedAt: '2026-11-21T01:00:00Z',
  }],
  clientsOnHold: [{ clientId: 40, name: 'Acme Transport', outstanding: 8000, daysOverdue: 50 }],
  topOverdue: [
    { clientId: 41, name: 'Bharat Logistics', outstanding: 10000, daysOverdue: 12 },
    { clientId: 40, name: 'Acme Transport', outstanding: 8000, daysOverdue: 50 },
  ],
  toRemind: [{
    clientId: 40, clientName: 'Acme Transport', phone: '9876543210', outstanding: 8000, daysOverdue: 50,
    overdueSince: '2026-10-09', stageDays: 30, onHold: true, lastRemindedAt: null, lastReminderMethod: null, upiPayLink: null,
  }],
}

describe('CollectionsTab', () => {
  resetScreenWidthAfterEach()

  beforeEach(() => {
    vi.mocked(collectionApi.getDashboard).mockResolvedValue(dashboard)
  })

  it('shows overdue, collected, holds, promises, age buckets and largest overdue accounts', async () => {
    renderWithProviders(<CollectionsTab pumpId={3} canRemind />)

    const figures = await screen.findByRole('region', { name: 'Collections at a glance' })
    expect(within(figures).getByText('₹18,000.00')).toBeInTheDocument()
    expect(within(figures).getByText('Overdue accounts: 2')).toBeInTheDocument()
    expect(within(figures).getByText('₹4,200.00')).toBeInTheDocument()
    const ages = screen.getByRole('region', { name: 'Age of the balance' })
    expect(within(ages).getByText('₹10,000.00')).toBeInTheDocument()
    const promises = screen.getByRole('region', { name: 'Promises' })
    expect(within(promises).getByRole('link', { name: 'Bharat Logistics' })).toHaveAttribute('href', '/dashboard/credit/41?records=followUp')
    expect(within(promises).getByText('Paid ₹500.00')).toBeInTheDocument()
    const onHold = screen.getByRole('region', { name: 'Credit on hold' })
    expect(within(onHold).getByText('50 days overdue', { exact: false })).toBeInTheDocument()
    const top = screen.getByRole('region', { name: 'Largest overdue accounts' })
    expect(within(top).getAllByRole('link').map((link) => link.textContent)).toEqual(['Bharat Logistics', 'Acme Transport'])
  })

  it('lists today\'s reminders with their actions on a phone', async () => {
    setScreenWidth(390)
    renderWithProviders(<CollectionsTab pumpId={3} canRemind />)

    const reminders = await screen.findByRole('region', { name: 'To remind today' })
    expect(within(reminders).getByText('30-day reminder')).toBeInTheDocument()
    expect(within(reminders).getByRole('button', { name: 'Copy message' })).toBeInTheDocument()
    expect(within(reminders).getByRole('button', { name: 'Mark reminded' })).toBeInTheDocument()
  })

  it('offers a retry when the dashboard cannot load', async () => {
    vi.mocked(collectionApi.getDashboard).mockRejectedValue(new Error('down'))
    renderWithProviders(<CollectionsTab pumpId={3} canRemind />)

    expect(await screen.findByText('Could not load collections.')).toBeInTheDocument()
  })
})
