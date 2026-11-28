import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { creditApi } from '../../../api/creditApi'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { CreditLimitHistory } from './CreditLimitHistory'

vi.mock('../../../api/creditApi', () => ({ creditApi: { getCreditLimitChanges: vi.fn() } }))

describe('CreditLimitHistory', () => {
  it('lists earlier limit changes with who made them and why', async () => {
    vi.mocked(creditApi.getCreditLimitChanges).mockResolvedValue([
      { id: 2, oldLimit: 20_000, newLimit: 50_000, oldUnlimited: false, newUnlimited: false, reason: 'Pays on time',
        changedByName: 'Ravi Owner', changedAt: '2026-11-20T10:00:00Z' },
      { id: 1, oldLimit: 0, newLimit: 20_000, oldUnlimited: true, newUnlimited: false, reason: 'Limit introduced',
        changedByName: 'Ravi Owner', changedAt: '2026-10-01T10:00:00Z' },
    ])
    renderWithProviders(<CreditLimitHistory pumpId={3} clientId={40} />)

    const history = within(screen.getByRole('region', { name: 'Limit changes' }))
    expect(await history.findByText('₹20,000.00 → ₹50,000.00')).toBeInTheDocument()
    expect(history.getByText('No limit → ₹20,000.00')).toBeInTheDocument()
    expect(history.getByText('Pays on time')).toBeInTheDocument()
    expect(creditApi.getCreditLimitChanges).toHaveBeenCalledWith(3, 40)
  })
})
