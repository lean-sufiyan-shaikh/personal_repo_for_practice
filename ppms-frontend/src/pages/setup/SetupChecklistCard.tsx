import { Link } from 'react-router-dom'
import { AlertTriangle, Check, Circle } from 'lucide-react'
import type { SetupChecklist } from '../../api/pumpApi'
import { StatusBadge } from '../../components/StatusBadge'
import { SETUP_STEP_LABELS, setupStepHref } from './setupChecklistSteps'

/** "4 of 6 done" with each step's state and a link to where it is set up. */
export function SetupChecklistCard({ checklist }: { checklist: SetupChecklist }) {
  const complete = checklist.doneCount === checklist.requiredCount
  return (
    <section className="ui-card p-0 overflow-hidden" aria-labelledby="setup-checklist-title">
      <div className="ui-toolbar">
        <h2 id="setup-checklist-title" className="ui-toolbar-title">Setup checklist</h2>
        <StatusBadge tone={complete ? 'ok' : 'warn'} label={`${checklist.doneCount} of ${checklist.requiredCount} done`} />
      </div>
      <div
        className="h-1.5 bg-surface-sunken"
        role="progressbar"
        aria-label="Setup progress"
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
        {checklist.items.map((item) => (
          <li key={item.step} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 text-sm">
            {item.done ? (
              <StatusBadge tone="ok" icon={Check} label="Done" />
            ) : item.optional ? (
              <StatusBadge tone="neutral" icon={Circle} label="Optional" />
            ) : (
              <StatusBadge tone="warn" icon={AlertTriangle} label="To do" />
            )}
            <span className="font-medium text-ink">{SETUP_STEP_LABELS[item.step]}</span>
            <span className="basis-full text-xs text-ink-3 sm:basis-auto sm:flex-1">{item.detail}</span>
            <Link to={setupStepHref(item.step)} className="ui-link text-xs" aria-label={`${item.done ? 'Review' : 'Set up'} ${SETUP_STEP_LABELS[item.step]}`}>
              {item.done ? 'Review' : 'Set up'}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}
