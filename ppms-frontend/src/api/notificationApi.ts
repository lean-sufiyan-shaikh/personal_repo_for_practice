import client from './client'

export type NotificationType =
  | 'LOW_STOCK'
  | 'PRICE_STALE'
  | 'DOCUMENT_EXPIRING'
  | 'CALIBRATION_DUE'
  | 'SHIFT_OVERDUE'
  | 'ZERO_SALE_SHIFT'
  | 'PRICE_CHANGE_OPEN_SHIFT'
  | 'ANCILLARY_LOW_STOCK'
  | 'AUTO_CLOSED_SHIFT'
  | 'SETTLEMENT_REMINDER'

export interface Notification {
  id: number
  pumpId: number
  type: NotificationType
  title: string
  message: string
  dedupKey: string
  createdAt: string
}

/** The newest notifications the signed-in user has not read (at most 100), and their total unread count. */
export interface NotificationList {
  notifications: Notification[]
  unreadCount: number
}

export const notificationApi = {
  getNotifications: (pumpId: number): Promise<NotificationList> =>
    client.get(`/pumps/${pumpId}/notifications`).then(r => r.data),

  /** Marks only the given notifications as read for the signed-in user; other users still see them. */
  markRead: (pumpId: number, ids: number[]): Promise<void> =>
    client.post(`/pumps/${pumpId}/notifications/mark-read`, { ids }).then(r => r.data),
}
