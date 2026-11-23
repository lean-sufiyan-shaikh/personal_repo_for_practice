import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CreditClient } from '../../api/creditApi'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import CreditPage from './CreditPage'

vi.mock('../../api/creditApi', () => ({ creditApi: { getLedgerSummary: vi.fn().mockResolvedValue([]) } }))
vi.mock('../../api/pumpApi', () => ({ pumpApi: { getCreditClientAccounts: vi.fn().mockResolvedValue([]) } }))
vi.mock('./LedgerTab', () => ({
  LedgerTab: ({ onSelectClient }: { onSelectClient: (client: CreditClient) => void }) => (
    <button type="button" onClick={() => onSelectClient({ id: 40 } as CreditClient)}>Open Acme</button>
  ),
}))
vi.mock('./CreditClientsTab', () => ({ CreditClientsTab: () => <p>Client list</p> }))
vi.mock('./ClientDetail', () => ({
  ClientDetail: ({ clientId, onBack }: { clientId: number; onBack: () => void }) => (
    <div>
      <p>Client {clientId}</p>
      <button type="button" onClick={onBack}>Back to all clients</button>
    </div>
  ),
}))

function renderCreditAt(path: string) {
  const router = createMemoryRouter([
    { path: '/dashboard', element: <p>Overview</p> },
    { path: '/dashboard/credit', element: <CreditPage /> },
    { path: '/dashboard/credit/:clientId', element: <CreditPage /> },
  ], { initialEntries: ['/dashboard', path] })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('CreditPage navigation', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'Owner', role: 'OWNER', assignedPumpId: null }, isAuthenticated: true })
    usePumpStore.setState({ selectedPumpId: 3 })
  })

  it('opens a client at its own address and browser Back returns to the ledger', async () => {
    const user = userEvent.setup()
    const router = renderCreditAt('/dashboard/credit?tab=ledger')

    await user.click(await screen.findByRole('button', { name: 'Open Acme' }))

    expect(await screen.findByText('Client 40')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard/credit/40')

    await act(() => router.navigate(-1))

    expect(await screen.findByRole('button', { name: 'Open Acme' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard/credit')
  })

  it('shows the client again after a refresh and its back button goes to the ledger, not out of Credit', async () => {
    const user = userEvent.setup()
    const router = renderCreditAt('/dashboard/credit/40')

    expect(await screen.findByText('Client 40')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Back to all clients' }))

    expect(await screen.findByRole('button', { name: 'Open Acme' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/dashboard/credit')
    expect(router.state.location.search).toBe('?tab=ledger')
  })

  it('keeps the chosen list tab in the address', async () => {
    const user = userEvent.setup()
    const router = renderCreditAt('/dashboard/credit')

    await user.click(await screen.findByRole('button', { name: 'Clients' }))

    expect(await screen.findByText('Client list')).toBeInTheDocument()
    expect(router.state.location.search).toBe('?tab=clients')
  })
})
