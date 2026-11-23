import type { Notification } from '../api/notificationApi'

export interface NotificationGroup {
  latest: Notification
  count: number
}

/** Collapses notifications with the same type, title and message into one entry, keeping the newest first. */
export function groupIdenticalNotifications(notifications: Notification[]): NotificationGroup[] {
  const groups = new Map<string, NotificationGroup>()
  for (const notification of notifications) {
    const key = JSON.stringify([notification.type, notification.title, notification.message])
    const group = groups.get(key)
    if (!group) {
      groups.set(key, { latest: notification, count: 1 })
      continue
    }
    group.count += 1
    if (notification.createdAt > group.latest.createdAt) group.latest = notification
  }
  return [...groups.values()]
}
