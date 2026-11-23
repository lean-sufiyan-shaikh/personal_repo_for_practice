import { X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { IconButton } from '../../../components/IconButton'
import { NumericInput } from '../../../components/NumericInput'
import { onActivateKey } from '../../../utils/keyboard'
import { FUEL_UNIT } from '../../../constants/fuel'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency } from '../../../utils/format'
import type { CreditClientSummary } from '../../../api/pumpApi'
import { CreditClientSearch } from './CreditClientSearch'
import { emptyNum } from './creditRow'
import { MoneyText } from '../../../components/AmountText'
import type { CreditRow } from './creditRow'

interface Props {
  row: CreditRow
  rowNumber: number
  isExpanded: boolean
  onExpandedChange: (rowId: number | null) => void
  creditClients: CreditClientSummary[]
  availableFuelTypes: string[]
  priceForFuelType: (fuelType: string) => number | null
  onUpdate: <K extends keyof CreditRow>(field: K, value: CreditRow[K]) => void
  onUpdateMulti: (updates: Partial<CreditRow>) => void
  onSelectClient: (clientId: number, clientName: string) => void
  onRemove: () => void
}

export function CreditEntryForm({
  row, rowNumber, isExpanded, onExpandedChange, creditClients, availableFuelTypes, priceForFuelType,
  onUpdate, onUpdateMulti, onSelectClient, onRemove,
}: Props) {
  const { t } = useTranslation(['shifts', 'common'])
  const isFilled = row.clientName.trim() !== '' && emptyNum(row.amount) > 0
  // Only root (parent/standalone) accounts in the main search
  const selectedParent = creditClients.find(c => c.id === row.clientId)
  const subAccounts = selectedParent?.isParent
    ? creditClients.filter(c => c.parentClientId === row.clientId)
    : []

  return (
    <div key={row.id}>
      {/* ── Collapsed summary row ── */}
      {!isExpanded ? (
        <div
          className="flex items-center justify-between px-4 py-3 hover:bg-surface-sunken transition-colors group"
          >
            <div
              className="flex flex-1 items-center gap-3 min-w-0 cursor-pointer"
              role="button"
              tabIndex={0}
              aria-label={t('creditEntry.editAria', { number: rowNumber })}
              onClick={() => onExpandedChange(row.id)}
              onKeyDown={onActivateKey(() => onExpandedChange(row.id))}
            >
            <span className="text-xs font-bold text-ink-3 shrink-0">#{rowNumber}</span>
            {isFilled ? (
              <>
                <span className="text-sm font-medium text-ink truncate">{row.clientName}</span>
                <span className="text-xs text-ink-3 shrink-0">{codeLabel(row.fuelType)}</span>
                <MoneyText value={emptyNum(row.amount)} className="text-sm font-semibold text-ok shrink-0" />
                {row.billNo && <span className="text-xs text-ink-3 shrink-0">· {row.billNo}</span>}
              </>
            ) : (
              <span className="text-xs text-ink-3 italic">{t('creditEntry.incomplete')}</span>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0 ml-2">
            <span className="text-xs text-primary">{t('common:actions.edit')}</span>
            <IconButton
              onClick={(e) => { e.stopPropagation(); onRemove() }}
              className="ui-btn-icon text-bad hover:text-bad transition-colors"
              label={t('creditEntry.deleteEntry')}
              icon={<X size={13} strokeWidth={2} />}
            />
          </div>
        </div>
      ) : (
        /* ── Expanded form ── */
        <div className="px-4 py-3 space-y-2.5 bg-surface-sunken">
          {/* Expanded header */}
          <div className="flex items-center justify-between">
            <button
              type="button"
              onClick={() => onExpandedChange(null)}
              className="flex items-center gap-1.5 text-xs font-semibold text-ink-2 hover:text-ink transition-colors"
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 15l7-7 7 7" />
              </svg>
              {t('creditEntry.entryNumber', { number: rowNumber })}
            </button>
            <button type="button" onClick={() => onRemove()}
              className="inline-flex items-center gap-1 text-bad hover:text-bad text-xs transition-colors">
              <X size={12} strokeWidth={2} />{t('common:actions.delete')}
            </button>
          </div>

          {/* Client search — root/parent accounts only */}
          <CreditClientSearch
            search={row.clientSearch}
            creditClients={creditClients}
            showDropdown={row.showDropdown}
            onSearchChange={(text) => onUpdateMulti({ clientSearch: text, clientName: text, clientId: null, showDropdown: true })}
            onShowDropdownChange={(show) => onUpdate('showDropdown', show)}
            onSelect={(c) => onSelectClient(c.id, c.name)}
            inputClassName="ui-search-shell__input text-sm"
            placeholder={t('creditEntry.searchClientShort')}
            showNoClientsHint
            blockOverdue={false}
          />

          {/* Sub-account picker — only shown when the selected parent has children */}
          {subAccounts.length > 0 && (
            <div>
              <span className="ui-label">
                {t('creditEntry.subAccount')} <span className="text-ink-3">{t('common:fields.optionalSuffix')}</span>
              </span>
              <div role="group" aria-label={t('creditEntry.subAccount')} className="ui-card p-0 overflow-hidden divide-y divide-border shadow-none border-border">
                {/* "Bill to parent" option */}
                <button
                  type="button"
                  onClick={() => onUpdateMulti({ subAccountId: null, subAccountName: '' })}
                  className={`w-full flex items-center gap-3 px-3 py-2 text-sm transition-colors text-left ${
                    row.subAccountId === null ? 'bg-info-bg' : 'hover:bg-bg'
                  }`}
                >
                  <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold shrink-0 ${
                    row.subAccountId === null ? 'bg-primary text-on-primary' : 'bg-surface-sunken text-ink-3'
                  }`}>
                    {row.clientName.charAt(0).toUpperCase()}
                  </div>
                  <span className={`flex-1 font-medium text-xs ${row.subAccountId === null ? 'text-primary' : 'text-ink-3'}`}>
                    {t('creditEntry.billToParent', { name: row.clientName })}
                  </span>
                  {row.subAccountId === null && (
                    <svg className="w-3.5 h-3.5 text-primary shrink-0" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                    </svg>
                  )}
                </button>
                {/* Sub-account options */}
                {subAccounts.map(s => {
                  const isSelected = row.subAccountId === s.id
                  return (
                    <button
                      key={s.id}
                      type="button"
                      onClick={() => onUpdateMulti({ subAccountId: s.id, subAccountName: s.name })}
                      className={`w-full flex items-center gap-3 px-3 py-2 text-sm transition-colors text-left ${
                        isSelected ? 'bg-info-bg' : 'hover:bg-bg'
                      }`}
                    >
                      <div className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold shrink-0 ${
                        isSelected ? 'bg-primary text-on-primary' : 'bg-surface-sunken text-ink-3'
                      }`}>
                        {s.name.charAt(0).toUpperCase()}
                      </div>
                      <span className={`flex-1 font-medium text-sm ${isSelected ? 'text-primary' : 'text-ink'}`}>
                        {s.name}
                      </span>
                      {s.phone && (
                        <span className="text-xs text-ink-3 shrink-0">{s.phone}</span>
                      )}
                      {isSelected && (
                        <svg className="w-3.5 h-3.5 text-primary shrink-0" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                        </svg>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>
          )}

          {/* Fuel type + Amount/Liters */}
          <div className="grid grid-cols-2 gap-2">
            <div>
              <span className="ui-label">
                {t('creditEntry.fuelType')} <span className="text-bad">*</span>
              </span>
              <SearchableSelect label={t('creditEntry.fuelType')}
                value={row.fuelType}
                onChange={v => onUpdateMulti({
                  fuelType: v,
                  amount: row.inputMode === 'liters' ? '' : row.amount,
                  liters: '',
                })}
                placeholder={t('common:select.placeholder')}
                accentColor="orange"
                size="sm"
                options={availableFuelTypes.map(ft => ({
                  value: ft,
                  label: codeLabel(ft),
                }))}
              />
            </div>
            <div>
              <div className="flex items-center justify-between mb-1">
                <span className="ui-label mb-0">
                  {row.inputMode === 'amount' ? t('creditEntry.amount') : t('creditEntry.liters')}{' '}
                  <span className="text-bad">*</span>
                </span>
                <div className="flex gap-1 text-xs">
                  <button type="button"
                    onClick={() => onUpdateMulti({ inputMode: 'amount', liters: '' })}
                    aria-pressed={row.inputMode === 'amount'}
                    aria-label={t('creditEntry.amountModeAria')}
                    className={`min-h-11 min-w-11 rounded-control border border-control px-2 font-medium transition-colors ${row.inputMode === 'amount' ? 'bg-primary text-on-primary' : 'bg-surface text-ink-3 hover:bg-bg'}`}>
                    ₹
                  </button>
                  <button type="button"
                    onClick={() => {
                      const price = priceForFuelType(row.fuelType)
                      const initLiters = price && row.amount
                        ? (parseFloat(row.amount) / price).toFixed(2)
                        : ''
                      onUpdateMulti({ inputMode: 'liters', liters: initLiters })
                    }}
                    disabled={!priceForFuelType(row.fuelType)}
                    aria-pressed={row.inputMode === 'liters'}
                    aria-label={t('creditEntry.litresModeAria')}
                    className={`min-h-11 min-w-11 rounded-control border border-control px-2 font-medium transition-colors ${row.inputMode === 'liters' ? 'bg-primary text-on-primary' : 'bg-surface text-ink-3 hover:bg-bg'} disabled:opacity-40 disabled:cursor-not-allowed`}>
                    L
                  </button>
                </div>
              </div>
              {row.inputMode === 'amount' ? (
                <>
                  <NumericInput aria-label={t('creditEntry.amountAria')} step="0.01" min="0.01" value={row.amount}
                    onChange={(e) => onUpdate('amount', e.target.value)}
                    placeholder="0.00"
                    className="ui-input-compact" />
                  {(() => {
                    const price = priceForFuelType(row.fuelType)
                    const unit = FUEL_UNIT[row.fuelType] ?? 'L'
                    const litersCalc = price && row.amount ? (parseFloat(row.amount) / price) : null
                    return litersCalc ? (
                      <p className="text-xs text-ink-3 mt-1">
                        {litersCalc.toFixed(2)} {unit} × ₹{price} = {formatCurrency(parseFloat(row.amount))}
                      </p>
                    ) : null
                  })()}
                </>
              ) : (
                <>
                  <NumericInput aria-label={t('creditEntry.quantityAria')} step="0.001" min="0.001" value={row.liters}
                    onChange={(e) => {
                      const price = priceForFuelType(row.fuelType)
                      const raw = parseFloat(e.target.value)
                      const amt = price && isFinite(raw) && raw >= 0 && raw <= 99_999
                        ? (raw * price).toFixed(2)
                        : ''
                      onUpdateMulti({ liters: e.target.value, amount: amt })
                    }}
                    placeholder="0.00"
                    className="ui-input-compact" />
                  {row.liters && row.amount && (() => {
                    const price = priceForFuelType(row.fuelType)
                    const unit = FUEL_UNIT[row.fuelType] ?? 'L'
                    return price ? (
                      <p className="text-xs text-ok mt-1 font-medium">
                        {parseFloat(row.liters).toFixed(2)} {unit} × ₹{price} = {formatCurrency(parseFloat(row.amount))}
                      </p>
                    ) : null
                  })()}
                </>
              )}
            </div>
          </div>

          {/* Bill No + Description */}
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="block">
                <span className="ui-label">{t('creditEntry.billNo')}</span>
                <input type="text" value={row.billNo}
                  onChange={(e) => onUpdate('billNo', e.target.value)}
                  placeholder={t('creditEntry.billNoPlaceholder')}
                  className="ui-input-compact" />
              </label>
            </div>
            <div>
              <label className="block">
                <span className="ui-label">{t('creditEntry.description')}</span>
                <input type="text" value={row.description}
                  onChange={(e) => onUpdate('description', e.target.value)}
                  placeholder={t('creditEntry.descriptionPlaceholder')}
                  className="ui-input-compact" />
              </label>
            </div>
          </div>

          {/* Add / Cancel actions */}
          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={() => onRemove()}
              className="ui-btn ui-btn-ghost text-xs px-3 py-1.5 min-h-0"
            >
              {t('common:actions.cancel')}
            </button>
            <button
              type="button"
              onClick={() => {
                if (!row.clientId || emptyNum(row.amount) <= 0) return
                onExpandedChange(null)
              }}
              disabled={!row.clientId || emptyNum(row.amount) <= 0}
              className="ui-btn ui-btn-primary text-xs px-3 py-1.5 min-h-0 disabled:opacity-40 disabled:cursor-not-allowed"
            >
              {t('common:actions.add')}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
