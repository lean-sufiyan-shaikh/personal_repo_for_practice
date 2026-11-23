import type { ApprovalItem } from '../../api/approvalApi'

export function approvalKey(item: Pick<ApprovalItem, 'kind' | 'id'>) {
  return `${item.kind}-${item.id}`
}

/** How long an item has waited, in the largest whole unit: "3 days", "5 hours", "just now". */
export function waitingFor(submittedAt: string | null, now: number): string {
  if (!submittedAt) return '—'
  const minutes = Math.floor((now - new Date(submittedAt).getTime()) / 60_000)
  if (minutes < 60) return minutes < 1 ? 'just now' : `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} hour${hours !== 1 ? 's' : ''}`
  const days = Math.floor(hours / 24)
  return `${days} day${days !== 1 ? 's' : ''}`
}
