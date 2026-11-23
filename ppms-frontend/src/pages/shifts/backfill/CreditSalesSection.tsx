import { X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { IconButton } from '../../../components/IconButton'
import { FUEL_LABELS } from '../../../constants/fuel'
import { codeLabel } from '../../../constants/labels'
import { SectionLabel } from './SectionLabel'
import { MoneyText } from '../../../components/AmountText'
import type { BackfillForm } from './useBackfillForm'

export function CreditSalesSection({ form }: { form: BackfillForm }) {
  const { t } = useTranslation('shifts')
  const { creditRows, expandedCreditId, setExpandedCreditId, creditClients, creditClientOptions, creditNum, addCreditRow, removeCreditRow, updateCreditRow } = form
  return (
                 <div className="rounded-card border border-border bg-warn-bg p-4 space-y-3">
                   <SectionLabel>{t('backfill.credit.title')}</SectionLabel>

                   <div className="flex items-center justify-between text-xs text-ink-2">
                     <span>{t('backfill.credit.total')}</span>
                     <MoneyText value={creditNum} className="font-semibold text-ink" />
                   </div>

                   {creditRows.map((row) => {
                     const isExpanded = expandedCreditId === row._key
                     return (
                       <div key={row._key} className="bg-surface rounded-card border border-border overflow-hidden">
                         {!isExpanded ? (
                           <div className="flex items-center justify-between px-3 py-2 text-xs">
                             <span className="font-medium text-ink truncate">
                               {row.clientName || t('backfill.credit.newEntry')}{row.amount ? ` · ₹${row.amount}` : ''}
                             </span>
                             <div className="flex items-center gap-2 shrink-0 ml-2">
                               <button type="button" onClick={() => setExpandedCreditId(row._key)}
                                 className="text-primary hover:underline">{t('backfill.credit.edit')}</button>
                               <IconButton onClick={() => removeCreditRow(row._key)}
                                 className="text-bad hover:text-bad-strong"
    label={t('backfill.credit.removeLabel')}
    icon={<X size={13} strokeWidth={2} />}
    />
                             </div>
                           </div>
                         ) : (
                           <div className="p-3 space-y-2">
                             <div className="grid grid-cols-2 gap-2">
                               <div>
                                 <span id={`backfill-credit-client-${row._key}`} className="ui-label text-xs">{t('backfill.credit.client')} <span className="text-bad">*</span></span>
                                 <SearchableSelect
                                   aria-labelledby={`backfill-credit-client-${row._key}`}
                                   value={row.clientId ? String(row.clientId) : ''}
                                   onChange={(value) => {
                                     const client = creditClients.find(c => c.id === Number(value))
                                     updateCreditRow(row._key, { clientId: client?.id ?? null, clientName: client?.name ?? '' })
                                   }}
                                   options={creditClientOptions}
                                   placeholder={t('backfill.credit.selectClient')}
                                   accentColor="orange"
                                   size="sm"
                                 />
                               </div>
                               <div>
                                 <label className="block">
                                   <span className="ui-label text-xs">{t('backfill.credit.amount')} <span className="text-bad">*</span></span>
                                   <input type="number" min="0.01" step="0.01" placeholder="0.00" value={row.amount}
                                     onChange={(e) => updateCreditRow(row._key, { amount: e.target.value })}
                                     className="ui-input" />
                                 </label>
                               </div>
                             </div>
                             <div className="grid grid-cols-2 gap-2">
                               <div>
                                 <label className="block">
                                   <span className="ui-label text-xs">{t('backfill.credit.fuelType')}</span>
                                   <select value={row.fuelType}
                                     onChange={(e) => updateCreditRow(row._key, { fuelType: e.target.value })}
                                     className="ui-input">
                                     <option value="">{t('backfill.credit.optionalChoice')}</option>
                                     {Object.keys(FUEL_LABELS).map((code) => (
                                       <option key={code} value={code}>{codeLabel(code)}</option>
                                     ))}
                                   </select>
                                 </label>
                               </div>
                               <div>
                                 <label className="block">
                                   <span className="ui-label text-xs">{t('backfill.credit.billNo')}</span>
                                   <input type="text" placeholder={t('backfill.credit.optional')} value={row.billNo}
                                     onChange={(e) => updateCreditRow(row._key, { billNo: e.target.value })}
                                     className="ui-input" />
                                 </label>
                               </div>
                             </div>
                             <div>
                               <label className="block">
                                 <span className="ui-label text-xs">{t('backfill.credit.description')}</span>
                                 <input type="text" placeholder={t('backfill.credit.optional')} value={row.description}
                                   onChange={(e) => updateCreditRow(row._key, { description: e.target.value })}
                                   className="ui-input" />
                               </label>
                             </div>
                             <div className="flex items-center justify-between pt-1">
                               <button type="button" onClick={() => removeCreditRow(row._key)}
                                 className="text-xs text-bad hover:text-bad-strong">{t('backfill.credit.removeEntry')}</button>
                               <button
                                 type="button"
                                 disabled={!row.clientId || !row.amount}
                                 onClick={() => setExpandedCreditId(null)}
                                 className="ui-btn ui-btn-primary min-h-0 px-3 py-1 text-xs disabled:opacity-40"
                               >{t('backfill.credit.done')}</button>
                             </div>
                           </div>
                         )}
                       </div>
                     )
                   })}

                   <button type="button" onClick={addCreditRow}
                     className="w-full border border-dashed border-warn rounded-card py-2 text-xs text-warn hover:border-warn hover:bg-warn-bg transition-colors">
                     {t('backfill.credit.add')}
                   </button>
                 </div>
  )
}
