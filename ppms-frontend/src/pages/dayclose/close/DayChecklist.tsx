import { Link } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, CircleX } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ChecklistItem } from '../../../api/dayCloseApi'
import { PageSection } from '../../../components/layout/PageSection'
import { checkText, fixLabel } from '../dayCloseText'

function CheckRow({ item }: { item: ChecklistItem }) {
  const Icon = item.done ? CheckCircle2 : item.blocking ? CircleX : AlertTriangle
  const colour = item.done ? 'text-ok' : item.blocking ? 'text-bad' : 'text-warn'
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
      <Icon size={18} strokeWidth={2} aria-hidden="true" className={`shrink-0 ${colour}`} />
      <span className={`min-w-0 flex-1 text-sm ${item.done ? 'text-ink' : colour}`}>{checkText(item)}</span>
      {!item.done && (
        <Link to={item.fixPath} className="ui-btn ui-btn-secondary ui-btn-compact min-h-11 sm:min-h-0">{fixLabel(item)}</Link>
      )}
    </li>
  )
}

/** What must be done before closing (blocking) and what the closer should know about (warnings). */
export function DayChecklist({ checks }: { checks: ChecklistItem[] }) {
  const { t } = useTranslation('dayclose')
  const blocking = checks.filter((item) => item.blocking)
  const warnings = checks.filter((item) => !item.blocking)
  return (
    <PageSection title={t('checklist.title')} description={t('checklist.description')} flush>
      <h3 className="px-4 pt-3 text-xs font-semibold text-ink-3">{t('checklist.mustDo')}</h3>
      <ul aria-label={t('checklist.mustDo')} className="divide-y divide-border">
        {blocking.map((item) => <CheckRow key={item.code} item={item} />)}
      </ul>
      <h3 className="border-t border-border px-4 pt-3 text-xs font-semibold text-ink-3">{t('checklist.warnings')}</h3>
      {warnings.length === 0 ? (
        <p className="px-4 py-3 text-sm text-ink-3">{t('checklist.noWarnings')}</p>
      ) : (
        <ul aria-label={t('checklist.warnings')} className="divide-y divide-border">
          {warnings.map((item) => <CheckRow key={item.code} item={item} />)}
        </ul>
      )}
    </PageSection>
  )
}
