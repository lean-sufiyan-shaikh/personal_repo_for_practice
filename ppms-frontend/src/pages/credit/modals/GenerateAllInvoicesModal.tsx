import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { creditApi } from '../../../api/creditApi'
import type { GenerateAllInvoicesResult } from '../../../api/creditApi'
import { Spinner } from '../../../components/Spinner'
import { Modal } from '../../../components/Modal'
import { parseApiError } from '../../../utils/apiError'
import { formatCurrency } from '../../../utils/format'
import { lastCompletedMonth } from '../creditDates'

// ─── Generate Invoices For All Clients Modal ─────────────────────────────────

interface GenerateAllInvoicesModalProps {
  pumpId: number
  onClose: () => void
}

export function GenerateAllInvoicesModal({ pumpId, onClose }: GenerateAllInvoicesModalProps) {
  const qc = useQueryClient()
  const [periodFrom, setPeriodFrom] = useState(() => lastCompletedMonth().periodFrom)
  const [periodTo, setPeriodTo] = useState(() => lastCompletedMonth().periodTo)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<GenerateAllInvoicesResult | null>(null)
  const periodValid = periodFrom !== '' && periodTo !== '' && periodFrom <= periodTo

  const preview = useQuery({
    queryKey: ['credit-invoices', pumpId, 'preview', periodFrom, periodTo],
    queryFn: () => creditApi.previewInvoicesForAllClients(pumpId, { periodFrom, periodTo }),
    enabled: periodValid && result === null,
  })
  const gstMissing = preview.data?.gstDetailsComplete === false
  const nothingToInvoice = preview.data?.clientsToInvoice === 0

  const mutation = useMutation({
    mutationFn: () => creditApi.generateInvoicesForAllClients(pumpId, { periodFrom, periodTo }),
    onSuccess: (runResult) => {
      qc.invalidateQueries({ queryKey: ['credit-invoices', pumpId] })
      setResult(runResult)
    },
    onError: (err) => setError(parseApiError(err, 'Failed to generate invoices')),
  })

  const handleSubmit = () => {
    if (!periodValid) { setError('Choose a period whose start is on or before its end.'); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title="Generate Invoices"
      subtitle="One invoice per client with uninvoiced credit sales"
      tone="info"
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          {result ? (
            <button onClick={onClose} className="ui-btn ui-btn-primary">Done</button>
          ) : (
            <>
              <button onClick={onClose} className="ui-btn ui-btn-secondary">Cancel</button>
              <button onClick={handleSubmit}
                disabled={mutation.isPending || !preview.data || gstMissing || nothingToInvoice}
                className="ui-btn ui-btn-primary">
                {mutation.isPending
                  ? <span className="flex items-center gap-1.5"><Spinner />Generating…</span>
                  : preview.data && !nothingToInvoice
                    ? `Generate ${preview.data.clientsToInvoice} invoice${preview.data.clientsToInvoice !== 1 ? 's' : ''}`
                    : 'Generate'}
              </button>
            </>
          )}
        </>
      }
    >
      {result ? (
        <div role="status" className="ui-alert ui-alert-success text-sm space-y-1">
          <p><strong>{result.invoicesIssued}</strong> invoice{result.invoicesIssued !== 1 ? 's' : ''} issued.</p>
          <p>{result.clientsSkipped} client{result.clientsSkipped !== 1 ? 's' : ''} had nothing to bill.</p>
          {result.clientsFailed > 0 && (
            <p className="text-bad">{result.clientsFailed} client{result.clientsFailed !== 1 ? 's' : ''} failed — try them one by one from the client screen.</p>
          )}
        </div>
      ) : (
        <>
          {gstMissing && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">
              <p className="font-semibold">Add the pump&apos;s GST details before issuing invoices.</p>
              <p>
                Invoices need the GSTIN, legal name and invoice prefix.{' '}
                <Link to="/dashboard/setup" onClick={onClose} className="ui-link">Open Setup → GST details</Link>
              </p>
            </div>
          )}
          <p className="text-sm text-ink-2">
            Clients with nothing to bill in the period are skipped. Interest is not included.
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <label className="block">
              <span className="ui-label">From</span>
              <input type="date" value={periodFrom} max={periodTo || undefined}
                onChange={e => setPeriodFrom(e.target.value)} className="text-sm" data-autofocus />
            </label>
            <label className="block">
              <span className="ui-label">To</span>
              <input type="date" value={periodTo} min={periodFrom || undefined}
                onChange={e => setPeriodTo(e.target.value)} className="text-sm" />
            </label>
          </div>
          <div role="status" aria-live="polite" className="ui-card-plain px-4 py-3 text-sm">
            {!periodValid ? (
              <span className="text-ink-3">Choose a period to see what would be invoiced.</span>
            ) : preview.isLoading ? (
              <span className="flex items-center gap-1.5 text-ink-3"><Spinner />Working out what would be invoiced…</span>
            ) : preview.isError ? (
              <span className="text-bad">{parseApiError(preview.error, 'Could not work out what would be invoiced')}</span>
            ) : preview.data && (
              <span className="text-ink">
                {nothingToInvoice
                  ? 'No client has uninvoiced credit sales in this period.'
                  : <><strong>{preview.data.clientsToInvoice}</strong> client{preview.data.clientsToInvoice !== 1 ? 's' : ''} would
                    be invoiced for <strong>{formatCurrency(preview.data.totalAmount)}</strong> in total.</>}
              </span>
            )}
          </div>
          {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
        </>
      )}
    </Modal>
  )
}
