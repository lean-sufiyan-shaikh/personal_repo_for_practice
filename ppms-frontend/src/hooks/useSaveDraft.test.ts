import { renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readDraft, removeDraft, useSaveDraft, writeDraft } from './useSaveDraft'

describe('draft storage', () => {
  it('reads back what was written, with the time it was saved', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000)
    writeDraft('form:1', { cash: '500' })

    expect(readDraft('form:1')).toEqual({ savedAt: 1_000, values: { cash: '500' } })
    vi.mocked(Date.now).mockRestore()
  })

  it('drops a draft older than two days', () => {
    localStorage.setItem('ppms_draft:form:1', JSON.stringify({ savedAt: Date.now() - 49 * 60 * 60 * 1000, values: {} }))

    expect(readDraft('form:1')).toBeNull()
    expect(localStorage.getItem('ppms_draft:form:1')).toBeNull()
  })

  it('treats unreadable storage as no draft and says why', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    localStorage.setItem('ppms_draft:form:1', '{not json')

    expect(readDraft('form:1')).toBeNull()
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('Could not read the saved draft form:1'), expect.any(SyntaxError))
    warn.mockRestore()
  })

  it('removes a draft', () => {
    writeDraft('form:1', { cash: '500' })
    removeDraft('form:1')
    expect(readDraft('form:1')).toBeNull()
  })
})

describe('useSaveDraft', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('saves only after typing pauses, and removes the draft once there is nothing to keep', () => {
    const { rerender } = renderHook(({ values, shouldSave }) => useSaveDraft('form:1', values, shouldSave), {
      initialProps: { values: { cash: '5' }, shouldSave: true },
    })
    rerender({ values: { cash: '50' }, shouldSave: true })
    vi.advanceTimersByTime(300)
    expect(readDraft('form:1')).toBeNull()

    vi.advanceTimersByTime(200)
    expect(readDraft('form:1')?.values).toEqual({ cash: '50' })

    rerender({ values: { cash: '50' }, shouldSave: false })
    vi.advanceTimersByTime(500)
    expect(readDraft('form:1')).toBeNull()
  })
})
