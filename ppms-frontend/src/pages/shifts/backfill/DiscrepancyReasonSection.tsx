import { useTranslation } from 'react-i18next'
import type { BackfillForm } from './useBackfillForm'

export function DiscrepancyReasonSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { discrepancyReason, setDiscrepancyReason } = form
  return (
    <div>
      <label className="block">
        <span className="ui-label">
        {t('backfill.reason.label')}
        <span className="text-ink-3 font-normal ml-1 text-xs">{t('backfill.reason.hint')}</span>
      </span>
        <textarea
          value={discrepancyReason}
          onChange={(e) => setDiscrepancyReason(e.target.value)}
          placeholder={t('backfill.reason.placeholder')}
          rows={2}
          className="ui-input resize-none"
        />
      </label>
    </div>
  )
}
