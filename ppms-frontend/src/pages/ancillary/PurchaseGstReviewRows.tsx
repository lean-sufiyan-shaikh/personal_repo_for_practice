import { useTranslation } from 'react-i18next'
import { useQuery } from '@tanstack/react-query'
import { pumpApi } from '../../api/pumpApi'
import { MoneyText } from '../../components/AmountText'
import { normaliseGstText } from '../../utils/gst'
import { ReviewList, ReviewRow } from './ReviewRow'
import { fmtDate } from './ancillaryFormat'
import { splitPurchaseGst } from './purchaseGst'
import type { PurchaseGstForm } from './purchaseGst'

export function PurchaseGstReviewRows({ pumpId, form, taxableValue }: {
  pumpId: number
  form: PurchaseGstForm
  taxableValue: number
}) {
  const { t } = useTranslation('ancillary')
  const { data: pumps = [] } = useQuery({ queryKey: ['myPumps'], queryFn: pumpApi.getMyPumps, staleTime: 5 * 60_000 })
  const pumpGstin = pumps.find(pump => pump.id === pumpId)?.gstin ?? null
  const split = splitPurchaseGst(taxableValue, form.gstRatePercent, form.supplierGstin, pumpGstin)
  return (
    <ReviewList>
      <ReviewRow label={t('stockIn.gst.supplierName')} value={form.supplierName.trim()} />
      <ReviewRow label={t('stockIn.gst.supplierGstin')} value={normaliseGstText(form.supplierGstin)} />
      <ReviewRow label={t('stockIn.gst.invoiceDate')} value={fmtDate(form.invoiceDate)} />
      <ReviewRow label={t('stockIn.gst.taxableValue')} value={<MoneyText value={taxableValue} />} />
      <ReviewRow label={t('stockIn.gst.rate')} value={t('stockIn.gst.rateOption', { rate: form.gstRatePercent })} />
      {split.sameState ? (
        <>
          <ReviewRow label={t('stockIn.gst.cgst')} value={<MoneyText value={split.cgst} />} />
          <ReviewRow label={t('stockIn.gst.sgst')} value={<MoneyText value={split.sgst} />} />
        </>
      ) : (
        <ReviewRow label={t('stockIn.gst.igst')} value={<MoneyText value={split.igst} />} />
      )}
      <ReviewRow label={t('stockIn.gst.billTotal')} value={<MoneyText value={split.total} />} isResult />
    </ReviewList>
  )
}
