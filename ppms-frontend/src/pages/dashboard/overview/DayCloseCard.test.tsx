import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { dayCloseApi } from '../../../api/dayCloseApi'
import type { UserRole } from '../../../types/auth'
import { useAuthStore } from '../../../store/authStore'
import { renderWithProviders } from '../../../test/renderWithProviders'
import { localDateInputValue } from '../../../utils/date'
import { listItem, page } from '../../dayclose/dayCloseTestData'
import { DayCloseCard } from './DayCloseCard'

vi.mock('../../../api/dayCloseApi', () => ({ dayCloseApi: { list: vi.fn() } }))

function signIn(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: 3 }, isAuthenticated: true })
}

const yesterday = localDateInputValue(-1)
const today = localDateInputValue()

describe('DayCloseCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    signIn('MANAGER')
  })

  it('asks to close yesterday while it is open', async () => {
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([]))
    renderWithProviders(<DayCloseCard pumpId={3} />)
    expect(await screen.findByText('Close yesterday')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open checklist' })).toHaveAttribute('href', `/dashboard/day-close?view=close&date=${yesterday}`)
  })

  it('asks to close today once yesterday is closed', async () => {
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([listItem({ businessDate: yesterday, status: 'CLOSED' })]))
    renderWithProviders(<DayCloseCard pumpId={3} />)
    expect(await screen.findByText('Close today')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Review' })).not.toBeInTheDocument()
  })

  it('tells an owner how many closed days wait for approval', async () => {
    signIn('OWNER')
    vi.mocked(dayCloseApi.list).mockResolvedValue(page([
      listItem({ businessDate: today, status: 'CLOSED' }),
      listItem({ businessDate: yesterday, status: 'CLOSED' }),
    ]))
    renderWithProviders(<DayCloseCard pumpId={3} />)
    expect(await screen.findByText('Yesterday and today are closed')).toBeInTheDocument()
    expect(screen.getByText('2 closed days are waiting for your approval')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Review' })).toHaveAttribute('href', '/dashboard/day-close?view=approvals')
  })

  it('is not shown to a role that cannot close days', () => {
    signIn('ACCOUNTANT')
    renderWithProviders(<DayCloseCard pumpId={3} />)
    expect(dayCloseApi.list).not.toHaveBeenCalled()
  })
})
