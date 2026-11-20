import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '../store/authStore'
import { usePermission } from './usePermission'

describe('usePermission', () => {
  it('denies everything when nobody is signed in', () => {
    useAuthStore.setState({ user: null, isAuthenticated: false })
    const { result } = renderHook(() => usePermission())
    expect(result.current.role).toBeUndefined()
    expect(result.current.hasAction('shift:open')).toBe(false)
    expect(result.current.hasPage('shifts')).toBe(false)
  })

  it('follows the signed-in role and updates when it changes', () => {
    useAuthStore.setState({ user: { userId: 1, fullName: 'A', role: 'OPERATOR', assignedPumpId: 1 }, isAuthenticated: true })
    const { result } = renderHook(() => usePermission())
    expect(result.current.hasAction('shift:open')).toBe(false)
    expect(result.current.hasPage('shifts')).toBe(true)

    act(() => useAuthStore.setState({ user: { userId: 1, fullName: 'A', role: 'MANAGER', assignedPumpId: 1 } }))
    expect(result.current.role).toBe('MANAGER')
    expect(result.current.hasAction('shift:open')).toBe(true)
  })
})
