import { useTranslation } from 'react-i18next'
import type { DayCloseComment, DayCloseVersion } from '../../../api/dayCloseApi'
import { StatusBadge } from '../../../components/StatusBadge'
import type { StatusTone } from '../../../components/StatusBadge'
import { PageSection } from '../../../components/layout/PageSection'
import { formatIstDateTime } from '../../../utils/date'

const KIND_TONE: Record<DayCloseComment['kind'], StatusTone> = { QUESTION: 'info', REPLY: 'neutral', REOPEN_REASON: 'warn' }

/** Questions, replies and re-open reasons, oldest first. */
export function CommentThread({ comments }: { comments: DayCloseComment[] }) {
  const { t } = useTranslation('dayclose')
  return (
    <PageSection title={t('comment.threadTitle')} className="print:hidden">
      {comments.length === 0 ? (
        <p className="text-sm text-ink-3">{t('comment.none')}</p>
      ) : (
        <ol className="space-y-3">
          {comments.map((comment) => (
            <li key={comment.id} className="space-y-1 rounded-control border border-border p-3">
              <div className="flex flex-wrap items-center gap-2 text-xs text-ink-3">
                <StatusBadge tone={KIND_TONE[comment.kind]} label={t(`comment.kind.${comment.kind}`)} />
                <span className="font-medium text-ink-2">{comment.authorName}</span>
                <span>{formatIstDateTime(comment.createdAt)}</span>
              </div>
              <p className="whitespace-pre-wrap text-sm text-ink">{comment.message}</p>
            </li>
          ))}
        </ol>
      )}
    </PageSection>
  )
}

interface VersionListProps {
  versions: DayCloseVersion[]
  current: number
  shown: number
  onShow: (version: number) => void
}

/** Every time the day was closed; an earlier close can be opened to see what it said then. */
export function VersionList({ versions, current, shown, onShow }: VersionListProps) {
  const { t } = useTranslation('dayclose')
  if (versions.length < 2) return null
  return (
    <PageSection title={t('versions.title')} className="print:hidden" flush>
      <ul className="divide-y divide-border">
        {versions.map((version) => (
          <li key={version.version} className="flex flex-wrap items-center gap-2 px-4 py-2 text-sm">
            <span className="font-semibold text-ink">{t('versions.label', { version: version.version })}</span>
            <span className="min-w-0 flex-1 text-ink-2">
              {t('versions.closedBy', { name: version.closedByName, time: formatIstDateTime(version.closedAt) })}
            </span>
            {version.version === shown ? (
              <StatusBadge tone="info" label={version.version === current ? t('versions.current') : t('versions.showing')} />
            ) : (
              <button type="button" onClick={() => onShow(version.version)} className="ui-btn ui-btn-secondary ui-btn-compact min-h-11 sm:min-h-0">
                {t('versions.show')}
              </button>
            )}
          </li>
        ))}
      </ul>
    </PageSection>
  )
}
