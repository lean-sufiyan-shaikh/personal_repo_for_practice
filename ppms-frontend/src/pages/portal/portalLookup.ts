import { isAxiosError } from 'axios'
import { PORTAL_PIN_PATTERN } from '../../constants/portalPin'

const DEFAULT_WAIT_MINUTES = 15

export type PortalError =
  | { kind: 'incomplete' | 'noMatch' | 'lookupFailed' }
  | { kind: 'tooManyAttempts'; minutes: number }

export function isLookupComplete(pumpId: string, phone: string, pin: string): boolean {
  return pumpId.trim() !== '' && /^\d{10}$/.test(phone) && PORTAL_PIN_PATTERN.test(pin)
}

export function lookupError(err: unknown): PortalError {
  const response = isAxiosError(err) ? err.response : undefined
  if (response?.status === 429) {
    const minutes = (response.data as { params?: { minutes?: unknown } } | undefined)?.params?.minutes
    return { kind: 'tooManyAttempts', minutes: typeof minutes === 'number' && minutes > 0 ? minutes : DEFAULT_WAIT_MINUTES }
  }
  if (response?.status === 404 || response?.status === 400) return { kind: 'noMatch' }
  return { kind: 'lookupFailed' }
}
