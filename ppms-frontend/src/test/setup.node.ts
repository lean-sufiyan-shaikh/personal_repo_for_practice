import { vi } from 'vitest'

// Test files share one worker; a fresh module registry keeps mocks and module state from leaking between files.
vi.resetModules()

const { initI18n } = await import('../i18n')
await initI18n('en')
