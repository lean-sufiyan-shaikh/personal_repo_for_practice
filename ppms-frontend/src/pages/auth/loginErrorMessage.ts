import { isAxiosError } from 'axios'
import { networkErrorMessage, serverMessage } from '../../utils/apiError'

export const WRONG_CREDENTIALS_MESSAGE = 'Wrong phone number or password.'
export const TOO_MANY_ATTEMPTS_MESSAGE = 'Too many failed attempts. Please wait a few minutes before trying again.'
export const SIGN_IN_REFUSED_MESSAGE = 'The server refused this sign-in. Reload the page and try again.'
export const SERVER_PROBLEM_MESSAGE = 'The server had a problem signing you in. Please try again in a few minutes.'

export function loginErrorMessage(err: unknown): string {
  const networkMessage = networkErrorMessage(err)
  if (networkMessage) return networkMessage
  const status = isAxiosError(err) ? err.response?.status : undefined
  if (status === 401) return WRONG_CREDENTIALS_MESSAGE
  if (status === 429) return serverMessage(err) ?? TOO_MANY_ATTEMPTS_MESSAGE
  if (status === 403) return SIGN_IN_REFUSED_MESSAGE
  if (status !== undefined && status >= 400 && status < 500) return serverMessage(err) ?? SERVER_PROBLEM_MESSAGE
  return SERVER_PROBLEM_MESSAGE
}
