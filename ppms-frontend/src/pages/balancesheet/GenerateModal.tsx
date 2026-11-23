import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { balanceSheetApi } from '../../api/balanceSheetApi'
import { parseApiError } from '../../utils/apiError'
import type { GenerateBalanceSheetRequest } from '../../api/balanceSheetApi'
import { shiftDefinitionApi } from '../../api/shiftDefinitionApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { localDateInputValue } from '../../utils/date'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'

interface GenerateModalProps {
  pumpId: number
  onClose: () => void
  /** Called with the new sheet's id instead of onClose once it is saved. */
  onGenerated: (sheetId: number) => void
}

export function GenerateModal({ pumpId, onClose, onGenerated }: GenerateModalProps) {
  const { t } = useTranslation(['balancesheet', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()

  const [reportType, setReportType] = useState<'SHIFT' | 'DAY'>('SHIFT')
  const [reportDate, setReportDate] = useState(() => localDateInputValue(0))
  const [shiftDefinitionId, setShiftDefinitionId] = useState<number | null>(null)
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirmRegenerate, setConfirmRegenerate] = useState(false)
  const [existingBsMsg, setExistingBsMsg] = useState<string | null>(null)

  const { data: shiftDefinitions = [], isLoading: defsLoading } = useQuery({
    queryKey: ['shift-definitions-active', pumpId],
    queryFn: () => shiftDefinitionApi.getActive(pumpId),
    enabled: reportType === 'SHIFT',
  })

  const selectedShiftDefinitionId = reportType === 'SHIFT'
    ? (shiftDefinitionId ?? shiftDefinitions[0]?.id ?? null)
    : null

  const mutation = useMutation({
    mutationFn: (req: GenerateBalanceSheetRequest) => balanceSheetApi.generate(pumpId, req),
    onSuccess: (generated) => {
      qc.invalidateQueries({ queryKey: ['balance-sheets', pumpId] })
      addToast(t('generate.success'), 'success')
      onGenerated(generated.id)
    },
    onError: (err) => {
      const msg: string = parseApiError(err, t('generate.failed'))
      // Backend returns "already exists" — offer the user a re-generate option
      if (msg.includes('already exists')) {
        setConfirmRegenerate(true)
        setExistingBsMsg(msg)
        setError(null)
      } else {
        setError(msg)
        addToast(msg, 'error')
      }
    },
  })

  const buildReq = (force = false): GenerateBalanceSheetRequest => {
    const req: GenerateBalanceSheetRequest = {
      reportType,
      reportDate,
      notes: notes.trim() || undefined,
      forceRegenerate: force || undefined,
    }
    if (selectedShiftDefinitionId !== null) {
      req.shiftDefinitionId = selectedShiftDefinitionId
    }
    return req
  }

  const handleSubmit = () => {
    setError(null)
    setConfirmRegenerate(false)
    mutation.mutate(buildReq())
  }

  const handleForceRegenerate = () => {
    setConfirmRegenerate(false)
    mutation.mutate(buildReq(true))
  }

  return (
    <Modal
      title={t('generate.title')}
      subtitle={t('generate.subtitle')}
      tone="info"
      size="lg"
      onClose={onClose}
      bodyClassName="space-y-4"
      footer={<>
          <button
            onClick={onClose}
            className="ui-btn ui-btn-secondary"
          >
            {t('common:actions.cancel')}
          </button>
          <button
            onClick={handleSubmit}
            disabled={mutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {mutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('common:actions.generating')}</span>
              : t('generate.submit')}
          </button>
      </>}
    >
          {/* Report type toggle */}
          <div>
            <span className="ui-label">{t('generate.reportType')}</span>
            <div role="group" aria-label={t('generate.reportType')} className="ui-card-plain flex gap-1 p-1">
              {(['SHIFT', 'DAY'] as const).map(type => (
                <button
                  key={type}
                  onClick={() => { setReportType(type); if (type === 'DAY') setShiftDefinitionId(null) }}
                  className={`ui-btn min-h-0 flex-1 px-3 py-2 text-sm font-medium ${
                    reportType === type
                      ? 'bg-primary text-on-primary'
                      : 'bg-surface text-ink-2 hover:bg-bg'
                  }`}
                >
                  {type === 'SHIFT' ? t('generate.shiftReport') : t('generate.dayReport')}
                </button>
              ))}
            </div>
            <p className="mt-1.5 text-xs text-ink-3">
              {reportType === 'SHIFT'
                ? t('generate.shiftHelp')
                : t('generate.dayHelp')}
            </p>
          </div>

          {/* Date */}
          <div>
            <label className="block">
              <span className="ui-label">{t('generate.businessDate')}</span>
              <input
                type="date"
                value={reportDate}
                onChange={e => setReportDate(e.target.value)}
                className="shadow-sm"
              />
            </label>
          </div>

          {/* Shift (only for SHIFT reports) */}
          {reportType === 'SHIFT' && (
            <div>
              <span className="ui-label">{t('generate.shift')}</span>
              {defsLoading ? (
                <p className="ui-help">{t('generate.loadingShifts')}</p>
              ) : shiftDefinitions.length === 0 ? (
                <p className="text-xs text-bad">{t('generate.noShiftDefinitions')}</p>
              ) : (
                <SearchableSelect label={t('generate.shift')}
                  value={selectedShiftDefinitionId !== null ? String(selectedShiftDefinitionId) : ''}
                  onChange={v => setShiftDefinitionId(Number(v))}
                  options={shiftDefinitions.map(d => ({ value: String(d.id), label: `${d.name} · ${d.windowLabel}` }))}
                />
              )}
            </div>
          )}

          {/* Notes */}
          <div>
            <label className="block">
              <span className="ui-label">{t('common:fields.notes')} <span className="text-ink-3">{t('common:fields.optionalSuffix')}</span></span>
              <textarea
                value={notes}
                onChange={e => setNotes(e.target.value)}
                rows={2}
                placeholder={t('generate.notesPlaceholder')}
                className="resize-none shadow-sm"
              />
            </label>
          </div>

          {confirmRegenerate && (
            <div className="ui-alert ui-alert-warning px-4 py-3 space-y-3">
              <p className="text-sm text-warn-strong font-medium">
                {t('generate.alreadyExists')}
              </p>
              {existingBsMsg && (
                <p className="text-xs text-warn">{existingBsMsg}</p>
              )}
              <p className="text-xs text-warn">
                {t('generate.revisionHelp')}
              </p>
              <div className="flex gap-2">
                <button
                  onClick={handleForceRegenerate}
                  disabled={mutation.isPending}
                  className="ui-btn min-h-0 px-3 py-1.5 text-xs bg-warn hover:bg-warn-strong text-white disabled:opacity-50"
                >
                  {mutation.isPending
                    ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('common:actions.generating')}</span>
                    : t('generate.confirmRevision')}
                </button>
                <button
                  onClick={() => setConfirmRegenerate(false)}
                  className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs text-warn border-warn hover:bg-warn-bg"
                >
                  {t('common:actions.cancel')}
                </button>
              </div>
            </div>
          )}
          {error && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">
              {error}
            </div>
          )}
    </Modal>
  )
}
