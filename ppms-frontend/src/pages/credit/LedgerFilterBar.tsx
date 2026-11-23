import { useTranslation } from 'react-i18next'
import type { FilterMode, LedgerFilters, TxTypeFilter } from './useLedgerFilters'
import type { MergedTx } from './ledgerGroups'
import { CsvExportButton } from '../../components/CsvExportButton'
import { csvFilename } from '../../utils/csv'
import type { CsvColumn } from '../../utils/csv'
import { codeLabel } from '../../constants/labels'

// Column headers stay English: accountants pass these files on to their CA as they are.
const LEDGER_COLUMNS: CsvColumn<MergedTx>[] = [
  { header: 'Date', value: (row) => row.businessDate, kind: 'date' },
  { header: 'Account', value: (row) => row.subAccountName ?? row.clientName },
  { header: 'Type', value: (row) => codeLabel(row.type) },
  { header: 'Reference', value: (row) => row.reference },
  { header: 'Detail', value: (row) => row.detail },
  { header: 'Amount', value: (row) => row.amount, kind: 'money' },
  { header: 'Running balance', value: (row) => row.runningBalance, kind: 'money' },
  { header: 'Status', value: (row) => codeLabel(row.status) },
  { header: 'Counted in balance', value: (row) => (row.countedInBalance ? 'Yes' : 'No') },
]

/** Each label is credit.json ledger.typeFilters.<value>. */
const TX_TYPE_FILTERS: { value: TxTypeFilter; color: string }[] = [
  { value: 'ALL',      color: 'slate' },
  { value: 'SALE',     color: 'orange' },
  { value: 'PAYMENT',  color: 'green' },
  { value: 'INTEREST', color: 'purple' },
]

export function LedgerFilterBar({ filters }: { filters: LedgerFilters }) {
  const { t } = useTranslation('credit')
  const {
    filterMode, setFilterMode, txTypeFilter, setTxTypeFilter, availableMonths,
    selectedMonth, setSelectedMonth, fromDate, setFromDate, toDate, setToDate, filtered,
  } = filters
  const exportRange = filterMode === 'range' ? { from: fromDate, to: toDate } : undefined

  return (
    <>
    {/* Filter toolbar */}
    <div className="flex flex-wrap items-center gap-3 mb-3">
      <h3 className="text-sm font-semibold text-ink-2 uppercase tracking-wide">{t('ledger.history')}</h3>
      <CsvExportButton
        filename={csvFilename(filterMode === 'month' && selectedMonth ? `credit-ledger-${selectedMonth}` : 'credit-ledger', exportRange)}
        columns={LEDGER_COLUMNS}
        rows={filtered}
      />
      <div className="flex items-center gap-1 ml-auto bg-surface-sunken rounded-control p-0.5">
        {(['all', 'month', 'range'] as FilterMode[]).map(m => (
          <button
            key={m}
            onClick={() => setFilterMode(m)}
            className={`px-3 py-1.5 rounded-control text-xs font-medium transition-all ${
              filterMode === m
                ? 'bg-surface text-ink shadow-raised'
                : 'text-ink-3 hover:text-ink'
            }`}
          >
            {t(`ledger.modes.${m}`)}
          </button>
        ))}
      </div>
    </div>
    {/* Transaction type filter */}
    <div className="flex items-center gap-2 mb-4">
      {TX_TYPE_FILTERS.map(({ value, color }) => (
        <button
          key={value}
          onClick={() => setTxTypeFilter(value)}
          className={`px-3 py-1 rounded-control text-xs font-medium border transition-all ${
            txTypeFilter === value
              ? color === 'slate'
                ? 'bg-ink border-ink text-surface'
                : color === 'orange'
                ? 'bg-warn-bg border-warn text-warn'
                : color === 'green'
                ? 'bg-ok-bg border-ok text-ok'
                : 'bg-info-bg border-info text-info'
              : 'bg-surface border-border text-ink-3 hover:border-control'
          }`}
        >
          {t(`ledger.typeFilters.${value}`)}
        </button>
      ))}
    </div>

    {/* Filter controls */}
    {filterMode === 'month' && (
      <div className="mb-4 flex flex-wrap gap-2">
        {availableMonths.length === 0 ? (
          <span className="text-xs text-ink-3">{t('ledger.noTransactions')}</span>
        ) : (
          availableMonths.map(m => (
            <button
              key={m}
              onClick={() => setSelectedMonth(prev => prev === m ? '' : m)}
              className={`px-3 py-1.5 rounded-control text-xs font-medium border transition-all ${
                selectedMonth === m
                  ? 'bg-primary border-primary text-on-primary'
                  : 'bg-surface border-border text-ink-2 hover:border-primary'
              }`}
            >
              {m}
            </button>
          ))
        )}
      </div>
    )}

    {filterMode === 'range' && (
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <div className="flex items-center gap-2">
          <label htmlFor="credit-ledger-from" className="text-xs text-ink-3 whitespace-nowrap">{t('period.from')}</label>
          <input id="credit-ledger-from" type="date" value={fromDate} onChange={e => setFromDate(e.target.value)}
            className="border border-control rounded-control px-3 py-1.5 text-sm" />
        </div>
        <div className="flex items-center gap-2">
          <label htmlFor="credit-ledger-to" className="text-xs text-ink-3 whitespace-nowrap">{t('period.to')}</label>
          <input id="credit-ledger-to" type="date" value={toDate} onChange={e => setToDate(e.target.value)}
            className="border border-control rounded-control px-3 py-1.5 text-sm" />
        </div>
        {(fromDate || toDate) && (
          <button onClick={() => { setFromDate(''); setToDate('') }}
            className="text-xs text-ink-3 hover:text-ink-2">{t('ledger.clear')}</button>
        )}
      </div>
    )}
    </>
  )
}
