import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { MoneyText } from '../../../components/AmountText'
import { NumericInput } from '../../../components/NumericInput'
import { COLLECTION_FIELDS, splitTotalOf } from './operatorCollections'
import type { CollectionField, OperatorCollectionRows } from './operatorCollections'

interface Props {
  shift: Shift
  rows: OperatorCollectionRows
  onChange: (rows: OperatorCollectionRows) => void
}

/** Optional: what each operator who worked the shift handed in. Each column must add up to the shift's figure. */
export function OperatorCollectionsSection({ shift, rows, onChange }: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const [open, setOpen] = useState(Object.keys(rows).length > 0)
  const workers = [
    { id: shift.operatorId, name: shift.operatorName },
    ...(shift.otherOperators ?? [])
      .filter((other, index, all) => all.findIndex((o) => o.operatorId === other.operatorId) === index)
      .map((other) => ({ id: other.operatorId, name: other.operatorName ?? '—' })),
  ]
  const labels: Record<CollectionField, string> = {
    cashCollected: t('close.payment.cash'),
    upiCollected: t('close.payment.upi'),
    cardCollected: t('close.payment.card'),
    fleetCardCollected: t('close.payment.fleetCard'),
    creditTotal: t('close.payment.creditTotal'),
  }

  const update = (operatorId: number, field: CollectionField, value: string) =>
    onChange({ ...rows, [operatorId]: { ...rows[operatorId], [field]: value } })

  return (
    <section className="ui-card space-y-3" aria-labelledby={`operator-split-${shift.id}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`operator-split-${shift.id}`} className="ui-title-sm">{t('operations.split.title')}</h3>
        <button type="button" className="ui-text-action" aria-expanded={open}
          onClick={() => { if (open) onChange({}); setOpen(!open) }}>
          {open ? t('operations.split.remove') : t('operations.split.add')}
        </button>
      </div>
      <p className="text-xs text-ink-3">{t('operations.split.help')}</p>
      {open && (
        <div className="space-y-3">
          {workers.map((worker) => (
            <fieldset key={worker.id} className="space-y-2">
              <legend className="text-sm font-semibold text-ink">{worker.name}</legend>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                {COLLECTION_FIELDS.map((field) => (
                  <label key={field} className="flex flex-col gap-1 text-xs text-ink-3">
                    {labels[field]}
                    <NumericInput step="0.01" min="0" value={rows[worker.id]?.[field] ?? ''}
                      onChange={(e) => update(worker.id, field, e.target.value)} />
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
          <dl className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-5">
            {COLLECTION_FIELDS.map((field) => (
              <div key={field}>
                <dt className="text-ink-3">{t('operations.split.splitTotal', { label: labels[field] })}</dt>
                <dd className="m-0 font-semibold text-ink"><MoneyText value={splitTotalOf(rows, field)} /></dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </section>
  )
}
