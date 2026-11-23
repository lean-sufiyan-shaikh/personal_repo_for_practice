import { Link } from 'react-router-dom'
import { AlertTriangle, Check, Circle } from 'lucide-react'
import type { SetupChecklist } from '../../api/pumpApi'
import { SETUP_STEP_LABELS, setupStepHref } from './setupChecklistSteps'

/** "4 of 6 done" with each step's state and a link to where it is set up. */
export function SetupChecklistCard({ checklist }: { checklist: SetupChecklist }) {
  const complete = checklist.doneCount === checklist.requiredCount
  return (
    <section className="ui-card p-0 overflow-hidden" aria-labelledby="setup-checklist-title">
      <div className="ui-toolbar">
        <h2 id="setup-checklist-title" className="ui-toolbar-title">Setup checklist</h2>
        <span className={`rounded-sm px-2 py-0.5 text-xs font-semibold ${complete ? 'bg-ok-bg text-ok' : 'bg-warn-bg text-warn'}`}>
          {checklist.doneCount} of {checklist.requiredCount} done
        </span>
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
      <ul className="divide-y divide-slate-100">
        {checklist.items.map((item) => (
          <li key={item.step} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-5 py-3 text-sm">
            {item.done ? (
              <Check size={16} strokeWidth={2.5} className="text-ok" aria-hidden="true" />
            ) : item.optional ? (
              <Circle size={16} strokeWidth={2} className="text-ink-3" aria-hidden="true" />
            ) : (
              <AlertTriangle size={16} strokeWidth={2} className="text-warn" aria-hidden="true" />
            )}
            <span className="font-medium text-ink">{SETUP_STEP_LABELS[item.step]}</span>
            <span className={`text-xs font-semibold ${item.done ? 'text-ok' : item.optional ? 'text-ink-3' : 'text-warn'}`}>
              {item.done ? 'Done' : item.optional ? 'Optional' : 'To do'}
            </span>
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
