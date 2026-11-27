import { isAxiosError } from 'axios'
import type { ApiErrorBody } from '../../../utils/apiError'

/** The client names a close was refused for, by reason; "—" when none. */
export interface BlockedCreditClients {
  overLimitClients: string
  onHoldClients: string
  overdueClients: string
}

/** The blocked clients named in a CREDIT_CLOSE_BLOCKED_CLIENTS refusal; null for any other error. */
export function blockedCreditOf(err: unknown): BlockedCreditClients | null {
  if (!isAxiosError<ApiErrorBody>(err) || err.response?.data?.code !== 'CREDIT_CLOSE_BLOCKED_CLIENTS') return null
  const params = (err.response.data.params ?? {}) as Record<string, unknown>
  const names = (key: string) => (typeof params[key] === 'string' ? params[key] as string : '—')
  return {
    overLimitClients: names('overLimitClients'),
    onHoldClients: names('onHoldClients'),
    overdueClients: names('overdueClients'),
  }
}
