import { isAxiosError } from 'axios'

export type ApiErrorBody = { message?: unknown; code?: unknown }

export const NO_CONNECTION_MESSAGE = 'No connection — check your network and try again.'
export const NO_SERVER_RESPONSE_MESSAGE = "Server didn't respond — please try again in a moment."

/** A message for a request that got no answer at all (offline or timed out), otherwise undefined. */
export function networkErrorMessage(err: unknown): string | undefined {
  if (!isAxiosError(err) || err.response) return undefined
  if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') return NO_SERVER_RESPONSE_MESSAGE
  if (err.code === 'ERR_NETWORK') return NO_CONNECTION_MESSAGE
  return undefined
}

/**
 * Extracts a safe, user-facing error message from an Axios error response.
 *
 * 4xx responses are business/validation errors from the backend — their message
 * is always safe to show directly to the user (no stack traces, no DB internals).
 *
 * Requests that got no answer say whether the network or the server is at fault.
 * 5xx and unexpected errors fall back to a generic message so raw server errors
 * are never exposed.
 */
export function parseApiError(err: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (!isAxiosError<ApiErrorBody>(err)) return fallback
  const networkMessage = networkErrorMessage(err)
  if (networkMessage) return networkMessage
  const status = err.response?.status
  const message = serverMessage(err)
  if (status !== undefined && status >= 400 && status < 500 && message !== undefined) {
    return message
  }
  return fallback
}

export function serverMessage(err: unknown): string | undefined {
  if (!isAxiosError<ApiErrorBody>(err)) return undefined
  const message = err.response?.data?.message
  return typeof message === 'string' && message.trim().length > 0 ? message.trim() : undefined
}
