import { useTranslation } from 'react-i18next'
import type { Supplier } from '../../../api/supplierApi'
import { FormField } from '../../../components/FormField'

interface Props {
  suppliers: Supplier[]
  supplierId: number | null
  onChange: (supplierId: number | null) => void
}

/** The oil company that billed the delivery; left unassigned, the delivery is in no supplier's balance. */
export function DeliverySupplierField({ suppliers, supplierId, onChange }: Props) {
  const { t } = useTranslation('suppliers')
  if (suppliers.length === 0) return null

  return (
    <FormField label={t('delivery.supplier')} help={supplierId === null ? t('delivery.unassignedHint') : undefined}>
      <select value={supplierId ?? ''} onChange={e => onChange(e.target.value ? Number(e.target.value) : null)}>
        <option value="">{t('delivery.unassigned')}</option>
        {suppliers.map(supplier => (
          <option key={supplier.id} value={supplier.id}>{supplier.name}</option>
        ))}
      </select>
    </FormField>
  )
}
