import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// Test files share one browser environment per worker; a fresh module registry keeps mocks and module state from leaking between files.
vi.resetModules()

// Tests render the real English resources, so text and role-name queries match what English users see.
const { initI18n } = await import('../i18n')
await initI18n('en')

// jsdom does not implement scrolling.
Element.prototype.scrollIntoView = function scrollIntoView() {}

afterEach(() => {
  cleanup()
  localStorage.clear()
})

// jsdom has no IntersectionObserver; report every observed element as visible.
class VisibleIntersectionObserver {
  private readonly callback: IntersectionObserverCallback
  constructor(callback: IntersectionObserverCallback) { this.callback = callback }
  observe(target: Element) {
    this.callback([{ isIntersecting: true, target } as IntersectionObserverEntry], this as unknown as IntersectionObserver)
  }
  unobserve() {}
  disconnect() {}
  takeRecords() { return [] }
}
globalThis.IntersectionObserver = VisibleIntersectionObserver as unknown as typeof IntersectionObserver
