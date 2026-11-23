import type { NotificationTarget, NotificationType } from '../api/notificationApi'
import { canAccessPath } from '../permissions/permissions'
import type { UserRole } from '../types/auth'

/** The address a notification opens, e.g. /dashboard/shifts?shift=123. */
export function notificationHref(target: NotificationTarget): string {
  const query = new URLSearchParams(target.params).toString()
  return query ? `${target.route}?${query}` : target.route
}

/** The address to open, or null when the user's role cannot open that page, so the alert is shown without a link. */
export function openableHref(target: NotificationTarget, role: UserRole | undefined): string | null {
  return canAccessPath(target.route, role) ? notificationHref(target) : null
}

export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  LOW_STOCK:               'Low tank stock',
  PRICE_STALE:             'Fuel prices not set',
  DOCUMENT_EXPIRING:       'Document expiry',
  CALIBRATION_DUE:         'Calibration overdue',
  CALIBRATION_DUE_SOON:    'Calibration due soon',
  SHIFT_OVERDUE:           'Shift overdue',
  ZERO_SALE_SHIFT:         'Zero-sale shift',
  PRICE_CHANGE_OPEN_SHIFT: 'Price changed during a shift',
  ANCILLARY_LOW_STOCK:     'Low product stock',
  AUTO_CLOSED_SHIFT:       'Shift force-closed',
  SETTLEMENT_REMINDER:     'Settlement reminder',
}
