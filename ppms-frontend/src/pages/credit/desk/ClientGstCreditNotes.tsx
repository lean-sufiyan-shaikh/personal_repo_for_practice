import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditDeskApi } from '../../../api/creditDeskApi'
import { MoneyText } from '../../../components/AmountText'
import { formatIstDate } from '../../../utils/date'

/** The client's GST credit notes, each printable like an invoice; nothing is shown while there are none. */
export function ClientGstCreditNotes({ pumpId, clientId }: { pumpId: number; clientId: number }) {
  const { t } = useTranslation('credit')
  const { data: notes = [] } = useQuery({
    queryKey: ['credit-invoices', pumpId, 'credit-notes', clientId],
    queryFn: () => creditDeskApi.listGstCreditNotes(pumpId, clientId),
  })
  if (notes.length === 0) return null
  return (
    <section className="space-y-2" aria-label={t('gstCreditNotes.title')}>
      <h3 className="text-sm font-semibold text-ink">{t('gstCreditNotes.title')}</h3>
      <ul className="space-y-2">
        {notes.map(note => (
          <li key={note.id} className="ui-card-plain px-4 py-3 flex flex-wrap items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-sm font-semibold text-ink">{note.creditNoteNumber}</p>
              <p className="text-xs text-ink-3 mt-0.5">
                {t('gstCreditNotes.against', {
                  invoice: note.invoiceNumber,
                  date: formatIstDate(note.issuedOn, { day: '2-digit', month: 'short', year: 'numeric' }),
                })}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <MoneyText value={note.total} className="text-sm font-bold text-ink" />
              <a href={creditDeskApi.gstCreditNotePrintUrl(pumpId, note.id)} target="_blank" rel="noopener noreferrer"
                className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs"
                aria-label={t('gstCreditNotes.printLabel', { number: note.creditNoteNumber })}>
                {t('invoices.viewPrint')}
              </a>
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}
