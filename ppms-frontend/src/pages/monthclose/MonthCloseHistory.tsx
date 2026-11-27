import { useTranslation } from 'react-i18next'
import type { MonthCloseVersion } from '../../api/monthCloseApi'
import { PageSection } from '../../components/layout/PageSection'
import { formatIstDateTime } from '../../utils/date'

interface Props {
  versions: MonthCloseVersion[]
  shownVersion: number | null
  onShow: (version: number) => void
}

/** Each close of the month, newest first, with who re-opened it and why. */
export function MonthCloseHistory({ versions, shownVersion, onShow }: Props) {
  const { t } = useTranslation('dayclose')
  return (
    <PageSection title={t('month.history')} className="print:hidden">
      <ul className="divide-y divide-border text-sm">
        {versions.map(v => (
          <li key={v.version} className="flex flex-wrap items-start justify-between gap-2 py-2">
            <div className="min-w-0">
              <p className="font-medium text-ink">
                {t('month.versionLine', { version: v.version, name: v.closedByName, time: formatIstDateTime(v.closedAt) })}
              </p>
              {v.reopenedAt && (
                <p className="text-xs text-warn">
                  {t('month.reopenedLine', { name: v.reopenedByName ?? '', time: formatIstDateTime(v.reopenedAt), reason: v.reopenReason ?? '' })}
                </p>
              )}
            </div>
            <button type="button" onClick={() => onShow(v.version)} disabled={shownVersion === v.version}
              className="ui-btn ui-btn-ghost ui-btn-compact">
              {shownVersion === v.version ? t('month.showing') : t('month.show')}
            </button>
          </li>
        ))}
      </ul>
    </PageSection>
  )
}
