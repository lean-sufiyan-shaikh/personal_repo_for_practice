import { Link } from 'react-router-dom'
import { AlertTriangle, Check, Circle } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { SetupChecklist } from '../../api/pumpApi'
import { StatusBadge } from '../../components/StatusBadge'
import { setupStepHref, setupStepLabel } from './setupChecklistSteps'
import { serverText } from '../../utils/serverText'

/** "4 of 6 done" with each step's state and a link to where it is set up. */
export function SetupChecklistCard({ checklist }: { checklist: SetupChecklist }) {
  const { t } = useTranslation(['setup', 'common'])
  const complete = checklist.doneCount === checklist.requiredCount
  return (
    <section className="ui-card p-0 overflow-hidden" aria-labelledby="setup-checklist-title">
      <div className="ui-toolbar">
        <h2 id="setup-checklist-title" className="ui-toolbar-title">{t('checklist.title')}</h2>
        <StatusBadge tone={complete ? 'ok' : 'warn'} label={t('checklist.doneCount', { done: checklist.doneCount, required: checklist.requiredCount })} />
      </div>
      <div
        className="h-1.5 bg-surface-sunken"
        role="progressbar"
        aria-label={t('checklist.progress')}
        aria-valuemin={0}
        aria-valuemax={checklist.requiredCount}
        aria-valuenow={checklist.doneCount}
      >
        <div
          className={`h-full ${complete ? 'bg-ok' : 'bg-primary'}`}
          style={{ width: `${(checklist.doneCount / Math.max(1, checklist.requiredCount)) * 100}%` }}
        />
      </div>
      <ul className="divide-y divide-border">
        {checklist.items.map((item) => {
          const stepLabel = setupStepLabel(item.step)
          const action = item.done ? t('common:actions.review') : t('checklist.setUp')
          return (
            <li key={item.step} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 text-sm">
              {item.done ? (
                <StatusBadge tone="ok" icon={Check} label={t('common:actions.done')} />
              ) : item.optional ? (
                <StatusBadge tone="neutral" icon={Circle} label={t('common:fields.optional')} />
              ) : (
                <StatusBadge tone="warn" icon={AlertTriangle} label={t('checklist.toDo')} />
              )}
              <span className="font-medium text-ink">{stepLabel}</span>
              <span className="basis-full text-xs text-ink-3 sm:basis-auto sm:flex-1">{serverText(item.detailCode, item.detailParams, item.detail)}</span>
              <Link to={setupStepHref(item.step)} className="ui-link text-xs" aria-label={t('checklist.actionLabel', { action, step: stepLabel })}>
                {action}
              </Link>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
