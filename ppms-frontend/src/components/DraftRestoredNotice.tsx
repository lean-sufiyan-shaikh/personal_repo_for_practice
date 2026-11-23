import { formatIstDateTime } from '../utils/date'

interface Props {
  savedAt: number
  onDiscard: () => void
}

/** Tells the user a form was refilled from entries saved on this device, with a way to start over. */
export function DraftRestoredNotice({ savedAt, onDiscard }: Props) {
  const savedTime = formatIstDateTime(new Date(savedAt), { hour: '2-digit', minute: '2-digit', hour12: true })
  return (
    <div className="ui-alert flex items-center justify-between gap-3 border-info bg-info-bg text-info">
      <p>Resumed your unsaved entries from {savedTime}</p>
      <button type="button" onClick={onDiscard} className="ui-btn ui-btn-secondary ui-btn-compact shrink-0">
        Discard
      </button>
    </div>
  )
}
