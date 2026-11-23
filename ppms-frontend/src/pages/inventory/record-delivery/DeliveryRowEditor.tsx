import { X } from 'lucide-react'
import { codeLabel } from '../../../constants/labels'
import { useTranslation } from 'react-i18next'
import { formatNumberIn } from '../../../i18n/format'
import type { TankStock } from '../../../api/inventoryApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { IconButton } from '../../../components/IconButton'
import { NumericInput } from '../../../components/NumericInput'
import { MoneyText } from '../../../components/AmountText'
import { limitDecimals } from '../inventoryFormat'
import type { DeliveryRow } from './deliveryRows'

interface Props {
  row: DeliveryRow
  rowNumber: number
  tanks: TankStock[]
  tankOptions: { value: string; label: string }[]
  isOwnerOrAdmin: boolean
  isExpanded: boolean
  onExpandedChange: (rowId: number | null) => void
  canRemove: boolean
  onRemove: () => void
  onChange: (field: keyof Omit<DeliveryRow, 'id'>, value: string) => void
}

export function DeliveryRowEditor({
  row, rowNumber, tanks, tankOptions, isOwnerOrAdmin, isExpanded, onExpandedChange, canRemove, onRemove, onChange,
}: Props) {
  const { t } = useTranslation(['inventory', 'common'])
  const tank          = tanks.find(t => t.tankId === Number(row.tankId))
  const rowCost       = isOwnerOrAdmin ? (parseFloat(row.qty) || 0) * (parseFloat(row.costPrice) || 0) : 0
  const enteredQty    = parseFloat(row.qty) || 0
  const afterStock    = tank ? tank.currentStock + enteredQty : null
  const availableSpace = tank && tank.capacity > 0 ? tank.capacity - tank.currentStock : null
  const rowOverflow   = availableSpace !== null && enteredQty > 0 && enteredQty > availableSpace
    const isFilled      = !!row.tankId && enteredQty > 0 && (isOwnerOrAdmin ? parseFloat(row.costPrice) > 0 : true)

  return (
    <div key={row.id} className={`ui-card overflow-hidden ${rowOverflow ? 'border border-bad bg-bad-bg' : 'ui-card-muted'}`}>
      {/* ── Collapsed summary row ── */}
      {!isExpanded ? (
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-3 min-w-0">
            <span className="text-xs font-bold text-ink-3 shrink-0">#{rowNumber}</span>
            {isFilled ? (
              <>
                <span className="text-sm font-medium text-ink truncate">
                  {tank?.tankIdentifier ?? '—'} · {codeLabel(tank?.fuelType)}
                </span>
                <span className="text-xs text-ink-3 shrink-0">{formatNumberIn(enteredQty)} L</span>
                {rowOverflow && (
                  <span className="text-xs text-bad font-semibold shrink-0">{t('row.overCapacity')}</span>
                )}
                {!rowOverflow && isOwnerOrAdmin && rowCost > 0 && (
                  <MoneyText value={rowCost} className="text-sm font-semibold text-primary shrink-0" />
                )}
              </>
            ) : (
              <span className="text-xs text-ink-3 italic">{t('row.incomplete')}</span>
            )}
          </div>
          <div className="flex items-center gap-2 shrink-0 ml-2">
            <button
              type="button"
              onClick={() => onExpandedChange(row.id)}
              className="text-xs text-primary hover:text-primary-strong transition-colors"
            >
              {t('common:actions.edit')}
            </button>
            {canRemove && (
              <IconButton
                onClick={onRemove}
                className="text-ink-3 hover:text-bad transition-colors"
                label={t('row.removeRow')}
                icon={<X size={14} strokeWidth={2} />}
              />
            )}
          </div>
        </div>
      ) : (
        /* ── Expanded form ── */
        <div className="p-4 space-y-3">
          {/* Row header */}
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-ink-3 uppercase tracking-wide">
              {t('row.tankNumber', { number: rowNumber })}
            </span>
            {canRemove && (
              <button
                type="button"
                onClick={onRemove}
                className="ui-btn ui-btn-ghost min-h-8 px-0 py-0 text-xs text-bad hover:text-bad-strong flex items-center gap-1"
              >
                <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
                </svg>
                {t('common:actions.remove')}
              </button>
            )}
          </div>

          {/* Tank selector */}
          <div>
            <span className="ui-label">{t('row.tank')}</span>
            <SearchableSelect label={t('row.tank')}
              value={row.tankId}
              onChange={v => onChange('tankId', v)}
              options={tankOptions}
              placeholder={t('row.selectTank')}
            />
          </div>

          {/* Qty + Cost */}
          <div className={`grid gap-3 ${isOwnerOrAdmin ? 'grid-cols-2' : 'grid-cols-1'}`}>
            <div>
              <label className="block">
                <span className="ui-label">
                {t('row.quantity')} <span aria-hidden="true" className="text-bad">*</span>
              </span>
                <NumericInput
                  step="0.001"
                  min="0.001"
                  value={row.qty}
                  onChange={e => onChange('qty', limitDecimals(e.target.value, 3))}
                  placeholder={t('row.quantityPlaceholder')}
                  className="shadow-raised"
                />
              </label>
            </div>
            {isOwnerOrAdmin && (
              <div>
                <label className="block">
                  <span className="ui-label">
                  {t('row.costPerLitre')} <span aria-hidden="true" className="text-bad">*</span>
                </span>
                  <NumericInput
                    step="0.01"
                    min="0.01"
                    value={row.costPrice}
                    onChange={e => onChange('costPrice', limitDecimals(e.target.value, 2))}
                    placeholder={t('row.costPlaceholder')}
                    className="shadow-raised"
                  />
                </label>
              </div>
            )}
          </div>
          {!isOwnerOrAdmin && (
            <p className="text-xs text-ink-3">
              {t('row.costManaged')}
            </p>
          )}

          {/* Per-row live previews */}
          {(afterStock !== null || (isOwnerOrAdmin && rowCost > 0)) && (
            <div className="flex items-center flex-wrap gap-3 text-xs">
              {isOwnerOrAdmin && rowCost > 0 && !rowOverflow && (
                <span className="bg-info-bg text-info px-2.5 py-1 rounded-control font-medium">
                  {t('row.deliveryCost', { amount: formatNumberIn(rowCost, { minimumFractionDigits: 2 }) })}
                </span>
              )}
              {afterStock !== null && row.qty && (
                rowOverflow ? (
                  <span className="bg-bad-bg text-bad px-2.5 py-1 rounded-control font-medium flex items-center gap-1">
                    <svg className="w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" />
                    </svg>
                    {t('row.exceedsCapacity', { value: formatNumberIn(availableSpace!, { minimumFractionDigits: 1 }) })}
                  </span>
                ) : (
                  <span className="text-ink-3">
                    {t('row.stockAfter')} <span className="font-semibold text-ink-2">
                      {formatNumberIn(afterStock, { minimumFractionDigits: 1 })} L
                    </span>
                    {tank && tank.capacity > 0 && ` / ${formatNumberIn(tank.capacity, { minimumFractionDigits: 0 })} L`}
                  </span>
                )
              )}
            </div>
          )}

          {/* Collapse button */}
          {isFilled && (
            <div className="flex justify-end pt-1">
              <button
                type="button"
                onClick={() => onExpandedChange(null)}
                className="ui-btn ui-btn-ghost text-xs px-3 py-1.5 min-h-0"
              >
                {t('common:actions.done')}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
