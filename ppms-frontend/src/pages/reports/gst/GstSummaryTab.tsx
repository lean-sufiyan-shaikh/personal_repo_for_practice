import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { gstSummaryApi, totalTaxOf } from '../../../api/gstSummaryApi'
import { MoneyText } from '../../../components/AmountText'
import { CsvExportButton } from '../../../components/CsvExportButton'
import { PageSection } from '../../../components/layout/PageSection'
import { parseApiError } from '../../../utils/apiError'
import { csvFilename } from '../../../utils/csv'
import { localDateInputValue } from '../../../utils/date'
import { ErrorBox, ReportFilterBar, ReportSummary, SummaryCard } from '../shared'
import { GstBucketsTable } from './GstBucketsTable'
import { GstPurchaseRegister } from './GstPurchaseRegister'
import { GST_CSV_COLUMNS, gstCsvRows } from './gstSummaryCsv'

/** A month's output tax, input tax and the net, in GSTR-3B style buckets for the pump's accountant. */
export function GstSummaryTab({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation(['reports', 'common'])
  const [monthInput, setMonthInput] = useState(() => localDateInputValue().slice(0, 7))
  const [month, setMonth] = useState(monthInput)

  const { data, isFetching, error } = useQuery({
    queryKey: ['report-gst-summary', pumpId, month],
    queryFn: () => gstSummaryApi.getSummary(pumpId, month),
  })
  const netTax = data ? totalTaxOf(data.netTax) : 0

  return (
    <div className="flex flex-col gap-(--density-gap)">
      <ReportFilterBar>
        <label className="flex flex-col gap-1 text-sm">
          <span className="ui-label">{t('gst.month')}</span>
          <input type="month" value={monthInput} onChange={e => setMonthInput(e.target.value)} />
        </label>
        <button type="button" onClick={() => setMonth(monthInput)} disabled={isFetching || !monthInput}
          className="ui-btn ui-btn-primary">
          {isFetching ? t('common:loading.short') : t('common:actions.generate')}
        </button>
        <CsvExportButton filename={csvFilename(`gst-summary-${month}`)} columns={GST_CSV_COLUMNS}
          rows={data ? gstCsvRows(data) : []} />
      </ReportFilterBar>

      <p role="note" className="ui-alert ui-alert-warning text-sm">{t('gst.accountantBanner')}</p>

      {error && <ErrorBox message={parseApiError(error, t('controls.loadFailed'))} />}

      {data && (
        <>
          {!data.pumpGstin && <p className="ui-alert ui-alert-warning text-sm">{t('gst.noPumpGstin')}</p>}
          <ReportSummary columns={3}>
            <SummaryCard label={t('gst.outputTax')} value={<MoneyText value={totalTaxOf(data.outwardTaxable)} />} color="text-ink" />
            <SummaryCard label={t('gst.inputTax')} value={<MoneyText value={totalTaxOf(data.inputTax)} />} color="text-ink" />
            <SummaryCard
              label={netTax < 0 ? t('gst.creditCarriedForward') : t('gst.netPayable')}
              value={<MoneyText value={Math.abs(netTax)} />}
              color={netTax > 0 ? 'text-bad' : 'text-good'}
            />
          </ReportSummary>

          <PageSection title={t('gst.bucketsTitle')} flush>
            <GstBucketsTable summary={data} />
          </PageSection>

          <ul className="text-sm text-ink-3 list-disc pl-5 space-y-1">
            <li>{t('gst.notes.creditSales')}</li>
            <li>{t('gst.notes.fuel')}</li>
            {data.counterSalesWithoutGst > 0 && (
              <li>{t('gst.notes.zeroRated', { count: data.counterSalesWithoutGst })}</li>
            )}
          </ul>

          <PageSection title={t('gst.registerTitle')} flush>
            <GstPurchaseRegister purchases={data.purchases} />
          </PageSection>
        </>
      )}
    </div>
  )
}
