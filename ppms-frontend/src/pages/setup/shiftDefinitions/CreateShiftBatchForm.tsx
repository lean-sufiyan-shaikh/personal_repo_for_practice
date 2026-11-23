import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { shiftDefinitionApi } from '../../../api/shiftDefinitionApi'
import type { CreateShiftDefinitionRequest } from '../../../api/shiftDefinitionApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { IconButton } from '../../../components/IconButton'
import { subtractOneMinute, TIME_OPTS, SORT_ORDER_OPTS, defaultRows } from './shiftDefinitionRows'
import type { ShiftRow } from './shiftDefinitionRows'

export function CreateShiftBatchForm({ pumpId, onClose }: { pumpId: number; onClose: () => void }) {
  const { t } = useTranslation(['setup', 'common'])
  const qc = useQueryClient()
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [hasEndDate, setHasEndDate] = useState(false)
  const [effectiveTo, setEffectiveTo] = useState('')
  const [rows, setRows] = useState<ShiftRow[]>(defaultRows)
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (reqs: CreateShiftDefinitionRequest[]) =>
      shiftDefinitionApi.createBatch(pumpId, reqs),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['shift-definitions', pumpId] })
      qc.invalidateQueries({ queryKey: ['shift-definitions-active', pumpId] })
      onClose()
    },
    onError: (err) => {
      setFormError(parseApiError(err, t('shiftDefs.form.saveFailed')))
    },
  })

  const addRow = () => {
    if (rows.length >= 4) return
    setRows(prev => [
      ...prev,
      { name: '', startTime: '06:00', endTime: '14:00', isNightShift: false, sortOrder: prev.length + 1 },
    ])
  }

  const removeRow = (idx: number) => setRows(prev => prev.filter((_, i) => i !== idx))

  const updateRow = (idx: number, patch: Partial<ShiftRow>) =>
    setRows(prev => prev.map((r, i) => (i === idx ? { ...r, ...patch } : r)))

  const handleSubmit = () => {
    setFormError(null)
    if (!effectiveFrom) { setFormError(t('shiftDefs.form.startRequired')); return }
    if (hasEndDate && !effectiveTo) { setFormError(t('shiftDefs.form.endRequired')); return }
    if (hasEndDate && effectiveTo <= effectiveFrom) { setFormError(t('shiftDefs.form.endAfterStart')); return }
    if (rows.some(r => !r.name.trim())) { setFormError(t('shiftDefs.form.nameRequired')); return }

    // Guard: only the night shift is allowed to cross midnight (endTime < startTime).
    // If a non-night shift has end < start, the user almost certainly picked AM instead of PM.
    const badRow = rows.find(r => !r.isNightShift && r.endTime <= r.startTime)
    if (badRow) {
      setFormError(t('shiftDefs.form.endsBeforeStart', { name: badRow.name }))
      return
    }

    // End times are exclusive upper bounds: store "end - 1 min" so consecutive shifts
    // can share a boundary (e.g., Night ends displayed as 9:00 AM, Morning starts at 9:00 AM).
    const resolvedEffectiveTo = hasEndDate ? effectiveTo : null
    const reqs: CreateShiftDefinitionRequest[] = rows.map(r => ({
      name: r.name.trim(),
      startTime: r.startTime,
      endTime: subtractOneMinute(r.endTime),
      isNightShift: r.isNightShift,
      sortOrder: r.sortOrder,
      effectiveFrom,
      effectiveTo: resolvedEffectiveTo,
    }))
    createMutation.mutate(reqs)
  }

  return (
    <div className="ui-card-plain p-4 space-y-4">
      <h3 className="text-sm font-semibold text-ink">{t('shiftDefs.form.title')}</h3>

      {/* Date range */}
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block">
            <span className="ui-label">{t('shiftDefs.form.startDate')} <span className="text-bad">*</span></span>
            <input
              type="date"
              value={effectiveFrom}
              onChange={e => setEffectiveFrom(e.target.value)}
              className="text-sm"
            />
          </label>
        </div>
        <div>
          <div className="flex items-center gap-2 mb-1">
            <span className="ui-label mb-0">{t('shiftDefs.form.endDate')}</span>
            <label className="flex items-center gap-1 text-xs text-ink-3 cursor-pointer">
              <input
                type="checkbox"
                checked={hasEndDate}
                onChange={e => { setHasEndDate(e.target.checked); if (!e.target.checked) setEffectiveTo('') }}
                className="w-3 h-3 accent-primary"
              />
              {t('shiftDefs.form.setEndDate')}
            </label>
          </div>
          <input aria-label={t('shiftDefs.form.endDateAria')}
            type="date"
            value={effectiveTo}
            onChange={e => setEffectiveTo(e.target.value)}
            disabled={!hasEndDate}
            min={effectiveFrom || undefined}
            className="text-sm disabled:bg-bg disabled:text-ink-3"
          />
          {!hasEndDate && (
            <p className="text-xs text-ink-3 mt-1">{t('shiftDefs.form.openEnded')}</p>
          )}
        </div>
      </div>
      <p className="text-xs text-ink-3 -mt-2">
        {t('shiftDefs.form.overlapHint')}
      </p>

      {/* Shift rows */}
      <div className="space-y-3">
        {rows.map((row, idx) => {
          const otherRows = rows.filter((_, i) => i !== idx)
          // Only block times used by rows that have been named — unnamed rows have default
          // placeholder times that should not restrict other rows' options.
          const configuredOtherRows = otherRows.filter(r => r.name.trim() !== '')
          const usedStarts = new Set(configuredOtherRows.map(r => r.startTime))
          const usedEnds   = new Set(configuredOtherRows.map(r => r.endTime))
          const startOpts  = TIME_OPTS.filter(o => !usedStarts.has(o.value))
          const endOpts    = TIME_OPTS.filter(o => !usedEnds.has(o.value))
          return (
          <div key={idx} className="grid gap-2 items-center md:grid-cols-[minmax(0,12rem)_minmax(8.5rem,1fr)_auto_minmax(8.5rem,1fr)_minmax(6.5rem,7.5rem)_auto]">
            <input aria-label={t('shiftDefs.form.shiftName')}
              placeholder={t('shiftDefs.form.shiftNamePlaceholder')}
              value={row.name}
              onChange={e => updateRow(idx, { name: e.target.value })}
              className="min-w-0 text-sm md:max-w-48"
            />
            <SearchableSelect label={t('shiftDefs.form.startTime')}
              value={row.startTime}
              onChange={v => updateRow(idx, { startTime: v })}
              options={startOpts}
              size="sm"
              className="min-w-0"
            />
            <span className="text-xs text-ink-3">→</span>
            <SearchableSelect label={t('shiftDefs.form.endTime')}
              value={row.endTime}
              onChange={v => updateRow(idx, { endTime: v })}
              options={endOpts}
              size="sm"
              className="min-w-0"
            />
            <SearchableSelect label={t('shiftDefs.form.displayOrder')}
              value={String(row.sortOrder)}
              onChange={v => updateRow(idx, { sortOrder: Number(v) })}
              options={SORT_ORDER_OPTS}
              size="sm"
              className="min-w-0"
            />
            <div className="flex items-center gap-1.5">
              <input
                id={`night-${idx}`}
                type="checkbox"
                checked={row.isNightShift}
                onChange={e => updateRow(idx, { isNightShift: e.target.checked })}
                className="w-3.5 h-3.5 accent-primary"
              />
              <label htmlFor={`night-${idx}`} className="text-xs text-ink-2 select-none">{t('shiftDefs.night')}</label>
              {rows.length > 1 && (
                <IconButton onClick={() => removeRow(idx)} className="ui-btn ui-btn-ghost ml-1 text-base leading-none text-bad hover:text-bad-strong"
                  label={t('shiftDefs.form.removeRow')}
                  icon={'×'}
                />
              )}
            </div>
          </div>
          )
        })}
      </div>

      {rows.length < 4 && (
        <button
          type="button"
          onClick={addRow}
          className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong"
        >
          {t('shiftDefs.form.addShift')}
        </button>
      )}

      {formError && <p role="alert" className="ui-error-text">{formError}</p>}

      <div className="flex gap-2 pt-1">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={createMutation.isPending}
          className="ui-btn ui-btn-primary"
        >
          {createMutation.isPending ? t('common:actions.saving') : t('shiftDefs.form.save')}
        </button>
        <button
          type="button"
          onClick={onClose}
          className="ui-btn ui-btn-secondary"
        >
          {t('common:actions.cancel')}
        </button>
      </div>
    </div>
  )
}
