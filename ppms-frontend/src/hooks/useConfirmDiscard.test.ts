import { renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useConfirmDiscard } from './useConfirmDiscard'

describe('useConfirmDiscard', () => {
  it('closes straight away when nothing has been entered', () => {
    const onClose = vi.fn()
    const confirm = vi.spyOn(window, 'confirm')
    const { result } = renderHook(() => useConfirmDiscard(false, onClose))
    result.current()
    expect(confirm).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('closes when the user confirms discarding entered details', () => {
    const onClose = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { result } = renderHook(() => useConfirmDiscard(true, onClose))
    result.current()
    expect(window.confirm).toHaveBeenCalledWith('Discard the details you have entered?')
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('stays open when the user cancels the discard', () => {
    const onClose = vi.fn()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { result } = renderHook(() => useConfirmDiscard(true, onClose))
    result.current()
    expect(onClose).not.toHaveBeenCalled()
  })
})
