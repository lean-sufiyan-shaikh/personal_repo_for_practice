import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { CreditClient } from '../../../api/pumpApi'
import { useAuthStore } from '../../../store/authStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import type { UserRole } from '../../../types/auth'
import { CreditClientSearch } from './CreditClientSearch'

const acme = { id: 2, name: 'Acme', phone: null, parentClientId: null, isParent: false } as unknown as CreditClient

function renderSearchFor(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'User', role, assignedPumpId: 3 }, isAuthenticated: true })
  renderWithProviders(
    <CreditClientSearch search="Zenith" creditClients={[acme]} showDropdown
      onSearchChange={vi.fn()} onShowDropdownChange={vi.fn()} onSelect={vi.fn()}
      inputClassName="" placeholder="Search client" />,
  )
}

describe('CreditClientSearch with no matching client', () => {
  it('tells an operator to ask the manager instead of linking to a page they cannot open', () => {
    renderSearchFor('OPERATOR')

    expect(screen.getByText('No match for "Zenith"')).toBeInTheDocument()
    expect(screen.getByText('Ask your manager to add this client.')).toBeInTheDocument()
    expect(screen.queryByText('+ Add client →')).not.toBeInTheDocument()
  })

  it('offers a manager the link to add the client', () => {
    renderSearchFor('MANAGER')

    expect(screen.getByRole('button', { name: /Add client/ })).toBeInTheDocument()
    expect(screen.queryByText('Ask your manager to add this client.')).not.toBeInTheDocument()
  })
})
