import { Building2 } from 'lucide-react'
import { useTranslation } from 'react-i18next'

/** The supplier an invoice is booked to, or "Not assigned" when it is in no supplier's balance. */
export function DeliverySupplierTag({ supplierName }: { supplierName: string | null }) {
  const { t } = useTranslation('suppliers')
  return (
    <span className={`inline-flex items-center gap-1 text-xs ${supplierName ? 'text-ink-2' : 'text-warn'}`}>
      <Building2 size={12} strokeWidth={2} aria-hidden="true" />
      {supplierName ?? t('delivery.unassigned')}
    </span>
  )
}
