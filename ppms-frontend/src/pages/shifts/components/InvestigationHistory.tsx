import { useTranslation } from 'react-i18next'
import type { InvestigationNote } from '../../../types/shift'
import { formatIstDateTime } from '../../../utils/date'

/** Every note written while a discrepancy was under investigation, oldest first; nothing when there are none. */
export function InvestigationHistory({ notes }: { notes: InvestigationNote[] | undefined }) {
  const { t } = useTranslation('shifts')
  if (!notes || notes.length === 0) return null

  return (
    <div className="space-y-1">
      <p className="text-xs font-semibold text-ink-3">{t('detail.investigationHistory')}</p>
      <ol className="divide-y divide-border rounded-card border border-border">
        {notes.map((note) => (
          <li key={`${note.writtenAt}-${note.note}`} className="px-3 py-2">
            <p className="text-ink">{note.note}</p>
            <p className="text-xs text-ink-3">
              {t('detail.noteBy', { name: note.writtenByName ?? t('labels.unknownUser'), at: formatIstDateTime(note.writtenAt) })}
            </p>
          </li>
        ))}
      </ol>
    </div>
  )
}
