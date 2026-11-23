import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../api/creditApi'
import type { CreditInvoice, InvoicePeriod } from '../../api/creditApi'
import { Pagination } from '../../components/Pagination'
import { SkeletonRows } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { Spinner } from '../../components/Spinner'
import { StatusBadge } from '../../components/StatusBadge'
import { invoiceStatusBadge } from '../../components/statusBadges'
import { MoneyText } from '../../components/AmountText'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { formatIstDate } from '../../utils/date'
import { CancelInvoiceModal } from './modals/CancelInvoiceModal'

// ─── Client Invoices Tab ─────────────────────────────────────────────────────

interface ClientInvoicesTabProps {
  pumpId: number
  clientId: number
  canIssueInvoices: boolean
}

const shortDate = (date: string) => formatIstDate(date, { day: '2-digit', month: 'short', year: 'numeric' })

export function ClientInvoicesTab({ pumpId, clientId, canIssueInvoices }: ClientInvoicesTabProps) {
  const { t } = useTranslation(['credit', 'common'])
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(10)
  const [cancelling, setCancelling] = useState<CreditInvoice | null>(null)

  const { data: defaultPeriod } = useQuery({
    queryKey: ['credit-invoice-default-period', pumpId, clientId],
    queryFn: () => creditApi.getDefaultInvoicePeriod(pumpId, clientId),
    enabled: canIssueInvoices,
  })

  const { data: invoices, isLoading, isError } = useQuery({
    queryKey: ['credit-invoices', pumpId, clientId, page, pageSize],
    queryFn: () => creditApi.listInvoices(pumpId, clientId, page, pageSize),
  })

  return (
    <div className="space-y-4">
      {cancelling && (
        <CancelInvoiceModal invoice={cancelling} pumpId={pumpId} onClose={() => setCancelling(null)} />
      )}

      {canIssueInvoices && defaultPeriod && (
        <GenerateInvoiceForm pumpId={pumpId} clientId={clientId} defaultPeriod={defaultPeriod}
          onGenerated={() => setPage(0)} />
      )}

      {isLoading ? (
        <SkeletonRows count={3} />
      ) : isError ? (
        <p role="alert" className="ui-alert ui-alert-danger text-sm">{t('invoices.loadFailed')}</p>
      ) : !invoices || invoices.content.length === 0 ? (
        <EmptyState icon="transactions" title={t('invoices.empty')}
          subtitle={canIssueInvoices ? t('invoices.emptyHint') : undefined} />
      ) : (
        <>
          <ul className="space-y-2" aria-label={t('invoices.listLabel')}>
            {invoices.content.map(invoice => (
              <li key={invoice.id} className="ui-card-plain px-4 py-3 flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="text-sm font-semibold text-ink">{invoice.invoiceNumber}</span>
                    <StatusBadge {...invoiceStatusBadge(invoice.status)} />
                  </div>
                  <p className="text-xs text-ink-3 mt-0.5">
                    {t('invoices.periodIssued', {
                      from: shortDate(invoice.periodFrom), to: shortDate(invoice.periodTo), issued: shortDate(invoice.issuedOn),
                    })}
                  </p>
                  {invoice.cancelReason && (
                    <p className="text-xs text-bad mt-0.5">{t('invoices.cancelReason', { reason: invoice.cancelReason })}</p>
                  )}
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                  <MoneyText value={invoice.grandTotal} className="text-sm font-bold text-ink" />
                  <a
                    href={creditApi.invoicePrintUrl(pumpId, invoice.id)}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs"
                    aria-label={t('invoices.viewPrintLabel', { number: invoice.invoiceNumber })}
                  >
                    {t('invoices.viewPrint')}
                  </a>
                  {canIssueInvoices && invoice.status === 'ISSUED' && (
                    <button type="button" onClick={() => setCancelling(invoice)}
                      className="ui-btn ui-btn-ghost min-h-0 px-3 py-1.5 text-xs text-bad border border-bad/30"
                      aria-label={t('invoices.cancelLabel', { number: invoice.invoiceNumber })}>
                      {t('common:actions.cancel')}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
          <Pagination
            data={invoices}
            onPageChange={setPage}
            onPageSizeChange={size => { setPageSize(size); setPage(0) }}
          />
        </>
      )}
    </div>
  )
}

interface GenerateInvoiceFormProps {
  pumpId: number
  clientId: number
  defaultPeriod: InvoicePeriod
  onGenerated: () => void
}

function GenerateInvoiceForm({ pumpId, clientId, defaultPeriod, onGenerated }: GenerateInvoiceFormProps) {
  const { t } = useTranslation('credit')
  const qc = useQueryClient()
  const { addToast } = useToastStore()
  const [periodFrom, setPeriodFrom] = useState(defaultPeriod.periodFrom)
  const [periodTo, setPeriodTo] = useState(defaultPeriod.periodTo)
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => creditApi.generateInvoice(pumpId, clientId, { periodFrom, periodTo }),
    onSuccess: (invoice) => {
      setError(null)
      qc.invalidateQueries({ queryKey: ['credit-invoices', pumpId] })
      addToast(t('invoices.issued', { number: invoice.invoiceNumber }), 'success')
      onGenerated()
    },
    onError: (err) => setError(parseApiError(err, t('invoices.generateFailed'))),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!periodFrom || !periodTo) { setError(t('invoices.bothDatesRequired')); return }
    if (periodFrom > periodTo) { setError(t('invoices.startAfterEnd')); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSubmit} className="ui-card-plain px-4 py-3 space-y-3" aria-label={t('invoices.generateHeading')}>
      <p className="text-sm font-semibold text-ink">{t('invoices.generateHeading')}</p>
      <p className="text-xs text-ink-3">
        {t('invoices.generateHelp')}
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <label className="block">
          <span className="ui-label">{t('period.from')}</span>
          <input type="date" value={periodFrom} max={periodTo || undefined}
            onChange={e => setPeriodFrom(e.target.value)} className="text-sm" />
        </label>
        <label className="block">
          <span className="ui-label">{t('period.to')}</span>
          <input type="date" value={periodTo} min={periodFrom || undefined}
            onChange={e => setPeriodTo(e.target.value)} className="text-sm" />
        </label>
      </div>
      {error && <p role="alert" className="ui-error-text">{error}</p>}
      <button type="submit" disabled={mutation.isPending} className="ui-btn ui-btn-primary">
        {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('generateInvoices.generating')}</span> : t('invoices.generateButton')}
      </button>
    </form>
  )
}
