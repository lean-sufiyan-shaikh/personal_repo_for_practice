import type { ReactNode } from 'react'
import { useAuthStore } from '../../../store/authStore'
import { roleLabel } from '../../../constants/labels'
import { formatIstDate } from '../../../utils/date'

/** One line of context for owners and accountants: where they are looking, who they are and the date. */
export function OverviewHeader({ focusName, children }: { focusName: string | undefined; children?: ReactNode }) {
  const user = useAuthStore((state) => state.user)
  const today = formatIstDate(new Date(), { weekday: 'long', day: 'numeric', month: 'long' })

  return (
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="ui-title-sm">{focusName ?? 'No pump selected'}</h1>
        <p className="text-sm text-ink-3">{user?.fullName} · {roleLabel(user?.role)} · {today}</p>
      </div>
      {children}
    </div>
  )
}
