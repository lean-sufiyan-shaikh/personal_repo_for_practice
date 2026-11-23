import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Plus, Pencil, Truck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { tankerApi } from '../../../api/tankerApi'
import type { Tanker, CreateTankerRequest } from '../../../api/tankerApi'
import { useToastStore } from '../../../store/toastStore'
import { ConfirmButton } from '../../../components/ConfirmButton'
import { IconButton } from '../../../components/IconButton'
import { TankerForm } from './TankerForm'
import { StatusBadge } from '../../../components/StatusBadge'
import { codeLabel } from '../../../constants/labels'
import { formatNumberIn } from '../../../i18n/format'

// ── Tankers content ───────────────────────────────────────────────────────────

export function TankersContent({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation(['setup', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()

  const { data: tankers = [], isLoading } = useQuery<Tanker[]>({
    queryKey: ['tankers', pumpId],
    queryFn: () => tankerApi.getTankers(pumpId),
  })

  const [showAdd,    setShowAdd]    = useState(false)
  const [editId,     setEditId]     = useState<number | null>(null)
  const [name,       setName]       = useState('')
  const [capacity,   setCapacity]   = useState('')
  const [tankerType, setTankerType] = useState<'OWN' | 'COMPANY'>('COMPANY')
  const [makeDefault, setMakeDefault] = useState(false)
  const [formError,  setFormError]  = useState<string | null>(null)

  const invalidate = () => qc.invalidateQueries({ queryKey: ['tankers', pumpId] })

  const resetForm = () => {
    setName(''); setCapacity(''); setTankerType('COMPANY'); setMakeDefault(false)
    setFormError(null); setShowAdd(false); setEditId(null)
  }

  const startEdit = (tanker: Tanker) => {
    setEditId(tanker.id); setName(tanker.name)
    setCapacity(String(tanker.capacityLitres)); setTankerType(tanker.tankerType)
    setMakeDefault(tanker.defaultTanker); setFormError(null); setShowAdd(false)
  }

  const createMutation = useMutation({
    mutationFn: (req: CreateTankerRequest) => tankerApi.createTanker(pumpId, req),
    onSuccess: () => { invalidate(); resetForm(); addToast(t('tankers.added'), 'success') },
    onError: (err: unknown) => setFormError(parseApiError(err, t('tankers.addFailed'))),
  })

  const updateMutation = useMutation({
    mutationFn: ({ id, req }: { id: number; req: CreateTankerRequest }) =>
      tankerApi.updateTanker(pumpId, id, req),
    onSuccess: () => { invalidate(); resetForm(); addToast(t('tankers.updated'), 'success') },
    onError: (err: unknown) => setFormError(parseApiError(err, t('tankers.updateFailed'))),
  })

  const deactivateMutation = useMutation({
    mutationFn: (id: number) => tankerApi.deactivateTanker(pumpId, id),
    onSuccess: () => { invalidate(); addToast(t('tankers.removed'), 'success') },
  })

  const setDefaultMutation = useMutation({
    mutationFn: (id: number) => tankerApi.setDefault(pumpId, id),
    onSuccess: () => { invalidate(); addToast(t('tankers.defaultUpdated'), 'success') },
  })

  function handleSubmit() {
    const cap = parseFloat(capacity)
    if (!name.trim()) { setFormError(t('tankers.nameRequired')); return }
    if (!capacity || isNaN(cap) || cap < 100) { setFormError(t('tankers.capacityMin')); return }
    setFormError(null)
    const req: CreateTankerRequest = {
      name: name.trim(), capacityLitres: cap, tankerType, defaultTanker: makeDefault,
    }
    if (editId !== null) {
      updateMutation.mutate({ id: editId, req })
    } else {
      createMutation.mutate(req)
    }
  }

  const isPending = createMutation.isPending || updateMutation.isPending

  return (
    <div className="space-y-4 pt-2">
      <p className="text-xs text-ink-3">
        {t('tankers.intro')}
      </p>

      {isLoading ? (
        <p className="text-xs text-ink-3">{t('tankers.loading')}</p>
      ) : tankers.length === 0 && !showAdd ? (
        <p className="text-xs text-ink-3">{t('tankers.empty')}</p>
      ) : (
        <div className="space-y-2">
          {tankers.map(tanker => (
            <div
              key={tanker.id}
              className={`flex items-center gap-3 rounded-card border px-4 py-3 ${
                editId === tanker.id ? 'border-info bg-info-bg' : 'border-border bg-surface'
              }`}
            >
              {editId === tanker.id ? (
                /* Inline edit form */
                <div className="flex-1 space-y-3">
                  <TankerForm
                    name={name} setName={setName}
                    capacity={capacity} setCapacity={setCapacity}
                    tankerType={tankerType} setTankerType={setTankerType}
                    makeDefault={makeDefault} setMakeDefault={setMakeDefault}
                    error={formError}
                  />
                  <div className="flex gap-2">
                    <button onClick={handleSubmit} disabled={isPending} className="ui-btn ui-btn-primary text-xs py-1.5 px-3 min-h-0">
                      {isPending ? t('actions.saving') : t('actions.save')}
                    </button>
                    <button onClick={resetForm} className="ui-btn ui-btn-secondary text-xs py-1.5 px-3 min-h-0">{t('common:actions.cancel')}</button>
                  </div>
                </div>
              ) : (
                <>
                  <div className="w-8 h-8 rounded-control bg-warn-bg flex items-center justify-center shrink-0">
                    <Truck size={14} strokeWidth={2} className="text-warn" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-semibold text-ink">{tanker.name}</span>
                      <span className="text-xs font-medium text-ink-2 bg-surface-sunken px-2 py-0.5 rounded-badge tabular-nums">
                        {t('tankers.capacityShort', { thousands: (tanker.capacityLitres / 1000).toFixed(0) })}
                      </span>
                      <StatusBadge tone="neutral" label={codeLabel(tanker.tankerType)} />
                      {tanker.defaultTanker && <StatusBadge tone="ok" icon={Check} label={t('tankers.default')} />}
                    </div>
                    <p className="text-xs text-ink-3 mt-0.5">
                      {t('tankers.capacityLong', { litres: formatNumberIn(tanker.capacityLitres) })}
                    </p>
                  </div>
                  <div className="flex items-center gap-1.5 shrink-0">
                    {!tanker.defaultTanker && (
                      <button
                        onClick={() => setDefaultMutation.mutate(tanker.id)}
                        disabled={setDefaultMutation.isPending}
                        className="ui-btn ui-btn-ghost text-xs py-1 px-2 min-h-0 text-ink-3"
                        title={t('tankers.setAsDefault')}
                      >
                        {t('tankers.setDefault')}
                      </button>
                    )}
                    <IconButton
                      onClick={() => startEdit(tanker)}
                      className="text-ink-3 hover:text-primary transition-colors rounded-control"
                      label={t('tankers.edit')}
                      icon={<Pencil size={13} />}
                    />
                    <ConfirmButton
                      onConfirm={() => deactivateMutation.mutate(tanker.id)}
                      disabled={deactivateMutation.isPending}
                      triggerAriaLabel={t('tankers.removeAria', { name: tanker.name })}
                      prompt={t('tankers.removePrompt')}
                      triggerClassName="p-1.5 text-ink-3 hover:text-bad transition-colors rounded-control"
                    />
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      )}

      {showAdd ? (
        <div className="rounded-card border border-info bg-info-bg p-4 space-y-3">
          <p className="text-xs font-semibold text-info uppercase tracking-wide">{t('tankers.newTanker')}</p>
          <TankerForm
            name={name} setName={setName}
            capacity={capacity} setCapacity={setCapacity}
            tankerType={tankerType} setTankerType={setTankerType}
            makeDefault={makeDefault} setMakeDefault={setMakeDefault}
            error={formError}
          />
          <div className="flex gap-2">
            <button onClick={handleSubmit} disabled={isPending} className="ui-btn ui-btn-primary text-xs py-1.5 px-3 min-h-0">
              {isPending ? t('actions.adding') : t('tankers.add')}
            </button>
            <button onClick={resetForm} className="ui-btn ui-btn-secondary text-xs py-1.5 px-3 min-h-0">{t('common:actions.cancel')}</button>
          </div>
        </div>
      ) : editId === null && (
        <button
          onClick={() => { resetForm(); setShowAdd(true) }}
          className="ui-btn ui-btn-secondary w-full border-dashed border-2 text-sm"
        >
          <Plus size={14} strokeWidth={2.5} />
          {t('tankers.add')}
        </button>
      )}
    </div>
  )
}
