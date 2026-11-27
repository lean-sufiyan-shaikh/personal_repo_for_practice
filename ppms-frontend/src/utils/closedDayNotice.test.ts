import { beforeEach, describe, expect, it } from 'vitest'
import type { UserRole } from '../types/auth'
import { useAuthStore } from '../store/authStore'
import { useToastStore } from '../store/toastStore'
import { offerReopenForClosedDay } from './closedDayNotice'

function closedDayError(date = '2026-11-26') {
  return { isAxiosError: true, response: { status: 400, data: { code: 'DAY_CLOSED', params: { date }, message: `${date} is closed.` } } }
}

function signIn(role: UserRole) {
  useAuthStore.setState({ user: { userId: 1, fullName: 'Someone', role, assignedPumpId: 3 }, isAuthenticated: true })
}

describe('offerReopenForClosedDay', () => {
  beforeEach(() => useToastStore.setState({ toasts: [] }))

  it('gives an owner a toast with the way to re-open that day', () => {
    signIn('OWNER')
    offerReopenForClosedDay(closedDayError())

    const [toast] = useToastStore.getState().toasts
    expect(toast.message).toBe('26 Nov 2026 is closed. Re-open it to make this change.')
    expect(toast.action).toEqual({ label: 'Re-open day', to: '/dashboard/day-close?date=2026-11-26' })
  })

  it('gives an admin the same way to re-open', () => {
    signIn('ADMIN')
    offerReopenForClosedDay(closedDayError())
    expect(useToastStore.getState().toasts).toHaveLength(1)
  })

  it('leaves a manager with the screen\'s own error, since only owners and admins can re-open', () => {
    signIn('MANAGER')
    offerReopenForClosedDay(closedDayError())
    expect(useToastStore.getState().toasts).toEqual([])
  })

  it('ignores every other error', () => {
    signIn('OWNER')
    offerReopenForClosedDay({ isAxiosError: true, response: { status: 400, data: { code: 'SHIFT_NOT_OPEN', params: {} } } })
    offerReopenForClosedDay(new Error('network'))
    expect(useToastStore.getState().toasts).toEqual([])
  })
})
