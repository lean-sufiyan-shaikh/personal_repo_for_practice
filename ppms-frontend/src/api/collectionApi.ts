import client from './client'

export type ReminderMethod = 'CALL' | 'IN_PERSON' | 'MESSAGE_COPIED'
export type PromiseStatus = 'OPEN' | 'KEPT' | 'MISSED' | 'CANCELLED'

/** An overdue account at a reminder stage nobody has reminded it of in this overdue episode; a parent covers its group. */
export interface ReminderDue {
  clientId: number
  clientName: string
  phone: string | null
  outstanding: number
  daysOverdue: number
  overdueSince: string
  stageDays: number
  onHold: boolean
  lastRemindedAt: string | null
  lastReminderMethod: ReminderMethod | null
  /** upi://pay link for the outstanding; null when the pump has no UPI details. */
  upiPayLink: string | null
}

export interface Reminder {
  id: number
  clientId: number
  overdueSince: string
  stageDays: number
  daysOverdue: number
  outstanding: number
  channel: string
  method: ReminderMethod
  note: string | null
  remindedByName: string
  remindedAt: string
}

export interface PaymentPromise {
  id: number
  clientId: number
  clientName: string
  amount: number
  promisedDate: string
  recordedOn: string
  note: string | null
  status: PromiseStatus
  /** Approved payments since the promise was recorded, as of its resolution; null while open. */
  paidAmount: number | null
  recordedByName: string
  recordedAt: string
  resolvedAt: string | null
}

export interface ClientFollowUp {
  clientId: number
  outstanding: number
  daysOverdue: number
  overdueSince: string | null
  reachedStage: number | null
  reminderDue: boolean
  /** Null when the pump never holds credit. */
  creditHoldDays: number | null
  onHold: boolean
  upiPayLink: string | null
  promises: PaymentPromise[]
  reminders: Reminder[]
}

export interface AccountFigure {
  clientId: number
  name: string
  outstanding: number
  daysOverdue: number
}

export interface CollectionDashboard {
  totalOutstanding: number
  totalOverdue: number
  overdueClientCount: number
  ageBuckets: { upTo30Days: number; days31To60: number; days61To90: number; over90Days: number }
  collectedThisMonth: number
  promisesDueToday: PaymentPromise[]
  /** Missed in the last 30 days, newest first. */
  missedPromises: PaymentPromise[]
  clientsOnHold: AccountFigure[]
  topOverdue: AccountFigure[]
  toRemind: ReminderDue[]
}

export interface MonthlyStatement {
  id: number
  clientId: number
  clientName: string
  periodFrom: string
  periodTo: string
  closingBalance: number
  transactionCount: number
  generatedAt: string
}

export interface CollectionSettings {
  /** Ascending; empty turns reminders off. */
  reminderStageDays: number[]
  /** Null turns the credit hold off. */
  creditHoldDays: number | null
  upiId: string | null
  upiPayeeName: string | null
  /** A manager's credit payment above this waits for approval; null lets any amount through. */
  creditPaymentApprovalThreshold: number | null
  /** A manager's credit payment dated more days back than this waits for approval. */
  backdatedPaymentApprovalDays: number
}

export interface RecordPromiseRequest {
  amount: number
  promisedDate: string
  note?: string
}

export interface EditPromiseRequest extends RecordPromiseRequest {
  reason?: string
}

export const collectionApi = {
  getDashboard: (pumpId: number) =>
    client.get<CollectionDashboard>(`/pumps/${pumpId}/collections/dashboard`).then(r => r.data),

  getDueReminders: (pumpId: number) =>
    client.get<ReminderDue[]>(`/pumps/${pumpId}/collections/reminders/due`).then(r => r.data),

  recordReminder: (pumpId: number, clientId: number, method: ReminderMethod, note?: string) =>
    client.post<Reminder>(`/pumps/${pumpId}/collections/clients/${clientId}/reminders`, { method, note }).then(r => r.data),

  getClientFollowUp: (pumpId: number, clientId: number) =>
    client.get<ClientFollowUp>(`/pumps/${pumpId}/collections/clients/${clientId}`).then(r => r.data),

  recordPromise: (pumpId: number, clientId: number, req: RecordPromiseRequest) =>
    client.post<PaymentPromise>(`/pumps/${pumpId}/collections/clients/${clientId}/promises`, req).then(r => r.data),

  editPromise: (pumpId: number, promiseId: number, req: EditPromiseRequest) =>
    client.put<PaymentPromise>(`/pumps/${pumpId}/collections/promises/${promiseId}`, req).then(r => r.data),

  cancelPromise: (pumpId: number, promiseId: number, reason: string) =>
    client.post<PaymentPromise>(`/pumps/${pumpId}/collections/promises/${promiseId}/cancel`, { reason }).then(r => r.data),

  /** month is YYYY-MM. */
  getStatementsOfMonth: (pumpId: number, month: string) =>
    client.get<MonthlyStatement[]>(`/pumps/${pumpId}/collections/statements`, { params: { month } }).then(r => r.data),

  getStatementsOfClient: (pumpId: number, clientId: number) =>
    client.get<MonthlyStatement[]>(`/pumps/${pumpId}/collections/statements`, { params: { clientId } }).then(r => r.data),

  // Same-origin stored page, opened in a new tab for printing
  statementPageUrl: (pumpId: number, statementId: number) =>
    `/api/pumps/${pumpId}/collections/statements/${statementId}`,

  getSettings: (pumpId: number) =>
    client.get<CollectionSettings>(`/pumps/${pumpId}/collection-settings`).then(r => r.data),

  updateSettings: (pumpId: number, settings: CollectionSettings) =>
    client.put<CollectionSettings>(`/pumps/${pumpId}/collection-settings`, settings).then(r => r.data),
}
