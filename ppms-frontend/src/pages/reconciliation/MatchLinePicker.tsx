import { useState } from 'react'
import type { FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { bankReconciliationApi } from '../../api/bankReconciliationApi'
import type { MatchedRecord, StatementLine } from '../../api/bankReconciliationApi'
import { Modal } from '../../components/Modal'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { formatCurrency } from '../../utils/format'
import { formatIstDate } from '../../utils/date'
import { parseApiError } from '../../utils/apiError'
import { amountsDiffer, lineAmount } from './reconciliationFormat'
import { CandidateList } from './CandidateList'

const SEARCH_DELAY_MS = 300

interface MatchLinePickerProps {
  pumpId: number
  line: StatementLine
  isSaving: boolean
  error: string | null
  onMatch: (record: MatchedRecord, note: string) => void
  onClose: () => void
}

/** Finds the settlement, deposit or payment behind a bank line; a note is asked for when the amounts differ. */
export function MatchLinePicker({ pumpId, line, isSaving, error, onMatch, onClose }: MatchLinePickerProps) {
  const { t } = useTranslation(['reconciliation', 'common'])
  const [search, setSearch] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [picked, setPicked] = useState<MatchedRecord | null>(null)
  const [note, setNote] = useState('')
  const [missingNote, setMissingNote] = useState(false)
  const filters = useDebouncedValue(JSON.stringify({ search: search.trim(), from, to }), SEARCH_DELAY_MS)

  const candidates = useQuery({
    queryKey: ['reconciliation-candidates', pumpId, line.id, filters],
    queryFn: () => {
      const { search: text, from: fromDate, to: toDate } = JSON.parse(filters) as { search: string; from: string; to: string }
      return bankReconciliationApi.getCandidates(pumpId, line.id, {
        search: text || undefined, from: fromDate || undefined, to: toDate || undefined,
      })
    },
  })

  const { amount, isCredit } = lineAmount(line)
  const needsNote = picked !== null && amountsDiffer(line, picked)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!picked) return
    if (needsNote && !note.trim()) {
      setMissingNote(true)
      return
    }
    onMatch(picked, note.trim())
  }

  return (
    <Modal
      title={t('match.title')}
      subtitle={t(isCredit ? 'match.subtitleCredit' : 'match.subtitleDebit', { date: formatIstDate(line.txnDate), amount: formatCurrency(amount) })}
      tone="info"
      size="lg"
      fullScreenOnPhone
      confirmDiscard={false}
      onClose={onClose}
      canClose={!isSaving}
      onSubmit={submit}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={isSaving} className="ui-btn ui-btn-secondary min-h-11">{t('common:actions.cancel')}</button>
          <LoadingButton type="submit" disabled={!picked} isLoading={isSaving} loadingLabel={t('common:actions.saving')} className="ui-btn-primary min-h-11">
            {t('match.confirm')}
          </LoadingButton>
        </>
      }
    >
      {line.narration && <p className="text-sm text-ink-2 break-words">{line.narration}</p>}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <FormField label={t('match.search')} help={t('match.searchHelp')} className="sm:col-span-3">
          <input type="search" value={search} onChange={(e) => setSearch(e.target.value)} data-autofocus />
        </FormField>
        <FormField label={t('common:fields.fromDate')}>
          <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
        </FormField>
        <FormField label={t('common:fields.toDate')}>
          <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
        </FormField>
      </div>
      <p className="text-xs text-ink-3">{t('match.defaultWindow')}</p>

      <CandidateList
        line={line}
        candidates={candidates.data}
        isLoading={candidates.isLoading}
        error={candidates.error ? parseApiError(candidates.error, t('match.loadFailed')) : null}
        picked={picked}
        onPick={(record) => { setPicked(record); setMissingNote(false) }}
      />

      {picked && (
        <FormField
          label={needsNote ? t('match.noteRequired') : t('match.noteOptional')}
          help={needsNote ? t('match.amountDiffers', { line: formatCurrency(amount), record: formatCurrency(picked.amount ?? 0) }) : undefined}
          error={missingNote ? t('match.missingNote') : undefined}>
          <textarea value={note} rows={2} maxLength={500} className="resize-none" aria-required={needsNote}
            onChange={(e) => { setNote(e.target.value); if (e.target.value.trim()) setMissingNote(false) }} />
        </FormField>
      )}

      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
