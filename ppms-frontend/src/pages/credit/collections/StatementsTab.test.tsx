import { fireEvent, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { collectionApi } from '../../../api/collectionApi'
import type { MonthlyStatement } from '../../../api/collectionApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { lastCompletedMonth } from '../creditDates'
import { ClientStatementsTab } from './ClientStatementsTab'
import { StatementsTab } from './StatementsTab'

vi.mock('../../../api/collectionApi', () => ({
  collectionApi: {
    getStatementsOfMonth: vi.fn(),
    getStatementsOfClient: vi.fn(),
    statementPageUrl: (pumpId: number, id: number) => `/api/pumps/${pumpId}/collections/statements/${id}`,
  },
}))

const statement: MonthlyStatement = {
  id: 7, clientId: 40, clientName: 'Acme Transport', periodFrom: '2026-10-01', periodTo: '2026-10-31',
  closingBalance: 12500, transactionCount: 9, generatedAt: '2026-10-31T21:00:00Z',
}

describe('StatementsTab', () => {
  beforeEach(() => {
    vi.mocked(collectionApi.getStatementsOfMonth).mockResolvedValue([statement])
    vi.mocked(collectionApi.getStatementsOfClient).mockResolvedValue([statement])
  })

  it('lists last month\'s statements by client with the closing balance and a print link', async () => {
    renderWithProviders(<StatementsTab pumpId={3} />)

    expect(await screen.findByText('Acme Transport')).toBeInTheDocument()
    expect(collectionApi.getStatementsOfMonth).toHaveBeenCalledWith(3, lastCompletedMonth().periodFrom.slice(0, 7))
    expect(screen.getByText('₹12,500.00')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open / print' })).toHaveAttribute('href', '/api/pumps/3/collections/statements/7')
  })

  it('loads another month and says when it has none', async () => {
    vi.mocked(collectionApi.getStatementsOfMonth).mockResolvedValue([])
    renderWithProviders(<StatementsTab pumpId={3} />)

    fireEvent.change(screen.getByLabelText('Month'), { target: { value: '2026-08' } })

    expect(await screen.findByText(/No statements for this month/)).toBeInTheDocument()
    expect(collectionApi.getStatementsOfMonth).toHaveBeenCalledWith(3, '2026-08')
  })

  it('shows a client\'s statements by month', async () => {
    renderWithProviders(<ClientStatementsTab pumpId={3} clientId={40} />)

    expect(await screen.findByText(/1 Oct 2026 – 31 Oct 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Entries: 9/)).toBeInTheDocument()
  })
})
