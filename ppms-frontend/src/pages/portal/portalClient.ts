import axios from 'axios'

// Uses a plain axios instance — no auth header, no JWT
export const publicClient = axios.create({ baseURL: '/api' })

export const PORTAL_SESSION_HEADER = 'X-Portal-Session'

export interface PortalTransaction {
  date: string
  type: string
  accountName: string | null
  detail: string | null
  /** What was sold; null for rows described for the pump's staff only. */
  reference: string | null
  amount: number
  countedInBalance: boolean
  disputed: boolean
}

export type PortalStatementMonth = 'CURRENT' | 'PREVIOUS'

const sessionHeaders = (sessionToken: string) => ({ headers: { [PORTAL_SESSION_HEADER]: sessionToken } })

export const portalApi = {
  getHistory: (sessionToken: string) =>
    publicClient.get<PortalTransaction[]>('/public/credit-history', sessionHeaders(sessionToken)).then(r => r.data),

  getStatementHtml: (sessionToken: string, month: PortalStatementMonth) =>
    publicClient.get<string>('/public/credit-statement', {
      ...sessionHeaders(sessionToken), params: { month }, responseType: 'text',
    }).then(r => r.data),
}
