import { describe, expect, it } from 'vitest'
import type { Notification } from '../api/notificationApi'
import { groupIdenticalNotifications } from './notificationGroups'

function reminder(id: number, createdAt: string, title = 'UPI Settlement Reminder'): Notification {
  return {
    id, pumpId: 3, type: 'SETTLEMENT_REMINDER', title, message: "Record today's UPI settlement", createdAt,
    read: false, target: { route: '/dashboard/settlements', params: {} },
  }
}

describe('groupIdenticalNotifications', () => {
  it('shows identical alerts once with their count, keeping the newest and every id', () => {
    const groups = groupIdenticalNotifications([
      reminder(1, '2026-11-22T12:30:00Z'),
      reminder(2, '2026-11-23T12:30:00Z'),
      reminder(3, '2026-11-23T12:30:00Z', 'Card Settlement Reminder'),
    ])

    expect(groups).toHaveLength(2)
    expect(groups[0]).toEqual({ latest: expect.objectContaining({ id: 2 }), count: 2, ids: [1, 2] })
    expect(groups[1]).toEqual({ latest: expect.objectContaining({ id: 3 }), count: 1, ids: [3] })
  })
})
