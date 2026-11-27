import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { pumpApi } from '../../api/pumpApi'
import { FormField } from '../../components/FormField'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'
import { LiveFigures } from '../../components/LiveFigures'
import { MoneyText } from '../../components/AmountText'
import { NumericInput } from '../../components/NumericInput'
import { isValidGstin } from '../../utils/gst'
import { PURCHASE_GST_RATES, splitPurchaseGst } from './purchaseGst'
import type { PurchaseGstForm } from './purchaseGst'

/** The GST printed on a counter-product purchase bill, with the CGST/SGST or IGST it works out to. */
export function PurchaseGstFields({ pumpId, form, onChange, taxableValue }: {
  pumpId: number
  form: PurchaseGstForm
  onChange: (changes: Partial<PurchaseGstForm>) => void
  taxableValue: number
}) {
  const { t } = useTranslation(['ancillary', 'common'])
  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps, staleTime: 5 * 60_000 })
  const pumpGstin = pumps.find(pump => pump.id === pumpId)?.gstin ?? null
  const gstinValid = form.supplierGstin.trim() === '' || isValidGstin(form.supplierGstin)
  const split = splitPurchaseGst(taxableValue, form.gstRatePercent, form.supplierGstin, pumpGstin)
  const gstinComplete = form.supplierGstin.trim().length === 15 && gstinValid

  return (
    <div className="space-y-3">
      {!pumpGstin && <p role="status" className="ui-alert ui-alert-warning">{t('stockIn.gst.pumpGstinMissing')}</p>}
      <FormGrid>
        <FormField label={t('stockIn.gst.supplierName')}>
          <input value={form.supplierName} maxLength={150} onChange={e => onChange({ supplierName: e.target.value })} />
        </FormField>
        <FormField label={t('stockIn.gst.supplierGstin')} error={gstinValid ? undefined : t('common:gst.gstinFormat')}>
          <input value={form.supplierGstin} maxLength={15} placeholder={t('common:gst.gstinPlaceholder')}
            onChange={e => onChange({ supplierGstin: e.target.value.toUpperCase() })} />
        </FormField>
        <FormField label={t('stockIn.gst.invoiceDate')}>
          <input type="date" value={form.invoiceDate} onChange={e => onChange({ invoiceDate: e.target.value })} />
        </FormField>
        <FormField label={t('stockIn.gst.taxableValue')} help={t('stockIn.gst.taxableValueHelp')}>
          <NumericInput unit="₹" min="0.01" step="0.01"
            value={form.taxableValue === '' ? '' : form.taxableValue}
            placeholder={taxableValue > 0 ? String(taxableValue) : undefined}
            onChange={e => onChange({ taxableValue: e.target.value === '' ? '' : parseFloat(e.target.value) || 0 })} />
        </FormField>
        <FormGridFullRow>
          <FormField label={t('stockIn.gst.rate')}>
            <select value={form.gstRatePercent} onChange={e => onChange({ gstRatePercent: Number(e.target.value) })}>
              {PURCHASE_GST_RATES.map(rate => (
                <option key={rate} value={rate}>{t('stockIn.gst.rateOption', { rate })}</option>
              ))}
            </select>
          </FormField>
        </FormGridFullRow>
      </FormGrid>
      <LiveFigures
        label={t('stockIn.gst.taxTitle')}
        figures={[
          ...(split.sameState || !gstinComplete
            ? [
                { label: t('stockIn.gst.cgst'), value: <MoneyText value={gstinComplete ? split.cgst : null} /> },
                { label: t('stockIn.gst.sgst'), value: <MoneyText value={gstinComplete ? split.sgst : null} /> },
              ]
            : [{ label: t('stockIn.gst.igst'), value: <MoneyText value={split.igst} /> }]),
          { label: t('stockIn.gst.billTotal'), value: <MoneyText value={gstinComplete ? split.total : null} />, isResult: true },
        ]}
      />
      {gstinComplete && (
        <p className="text-sm text-ink-3">
          {split.sameState ? t('stockIn.gst.sameStateNote') : t('stockIn.gst.otherStateNote')}
        </p>
      )}
    </div>
  )
}
