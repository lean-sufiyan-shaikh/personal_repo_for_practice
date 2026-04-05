import { useState, useRef, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { notificationApi } from '../api/notificationApi'

interface Props {
  pumpId: number
}

export default function NotificationBell({ pumpId }: Props) {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const { data: notifications = [] } = useQuery({
    queryKey:        ['notifications', pumpId],
    queryFn:         () => notificationApi.getNotifications(pumpId),
    refetchInterval: 60_000, // poll every 60 seconds
  })

  const markReadMutation = useMutation({
    mutationFn: () => notificationApi.markAllRead(pumpId),
    onSuccess:  () => qc.invalidateQueries({ queryKey: ['notifications', pumpId] }),
  })

  // Close dropdown when clicking outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const unread = notifications.filter(n => !n.readAt)

  const handleOpen = () => {
    setOpen(s => !s)
  }

  const handleMarkAllRead = () => {
    if (unread.length > 0) markReadMutation.mutate()
  }

  const TYPE_ICONS: Record<string, string> = {
    LOW_STOCK:          '🛢',
    PRICE_STALE:        '⚠️',
    DOCUMENT_EXPIRING:  '📄',
    CALIBRATION_DUE:    '🔧',
    SHIFT_OVERDUE:      '⏰',
  }

  return (
    <div ref={ref} className="relative">
      {/* Bell button */}
      <button
        onClick={handleOpen}
        className="relative w-8 h-8 flex items-center justify-center text-slate-300 hover:text-white transition-colors"
        title="Notifications"
      >
        <span className="text-lg">🔔</span>
        {unread.length > 0 && (
          <span className="absolute -top-0.5 -right-0.5 w-4 h-4 bg-red-500 text-white text-xs font-bold rounded-full flex items-center justify-center leading-none">
            {unread.length > 9 ? '9+' : unread.length}
          </span>
        )}
      </button>

      {/* Dropdown panel */}
      {open && (
        <div className="absolute right-0 top-10 w-80 bg-white rounded-xl shadow-xl border border-slate-200 z-50 overflow-hidden">
          <div className="px-4 py-3 border-b border-slate-100 flex items-center justify-between">
            <p className="text-sm font-semibold text-slate-700">Notifications</p>
            {unread.length > 0 && (
              <button
                onClick={handleMarkAllRead}
                disabled={markReadMutation.isPending}
                className="text-xs text-blue-500 hover:text-blue-700 font-medium"
              >
                Mark all read
              </button>
            )}
          </div>

          {notifications.length === 0 ? (
            <div className="px-4 py-6 text-center">
              <p className="text-sm text-slate-400">No notifications</p>
              <p className="text-xs text-slate-300 mt-1">You're all caught up</p>
            </div>
          ) : (
            <div className="max-h-80 overflow-y-auto divide-y divide-slate-100">
              {notifications.map(n => (
                <div
                  key={n.id}
                  className={`px-4 py-3 ${n.readAt ? 'opacity-60' : 'bg-blue-50/50'}`}
                >
                  <div className="flex items-start gap-2">
                    <span className="text-base flex-shrink-0 mt-0.5">{TYPE_ICONS[n.type] ?? '📌'}</span>
                    <div className="min-w-0">
                      <p className="text-xs font-semibold text-slate-700">{n.title}</p>
                      <p className="text-xs text-slate-500 mt-0.5 leading-relaxed">{n.message}</p>
                    </div>
                    {!n.readAt && (
                      <span className="w-2 h-2 bg-blue-500 rounded-full flex-shrink-0 mt-1" />
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
