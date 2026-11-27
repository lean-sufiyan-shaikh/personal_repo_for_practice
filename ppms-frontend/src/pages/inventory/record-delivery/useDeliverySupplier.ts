import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { supplierApi } from '../../../api/supplierApi'
import type { Supplier } from '../../../api/supplierApi'
import { useOnValuesChange } from '../../../hooks/useOnValuesChange'

/** The pump's default supplier, else its only supplier, else none. */
export function preselectedSupplierId(suppliers: Supplier[]): number | null {
  const defaultSupplier = suppliers.find(supplier => supplier.defaultSupplier)
  if (defaultSupplier) return defaultSupplier.id
  return suppliers.length === 1 ? suppliers[0].id : null
}

/** The supplier a new tanker delivery is booked to, preselected once the pump's suppliers have loaded. */
export function useDeliverySupplier(pumpId: number) {
  const { data: suppliers = [] } = useQuery({
    queryKey: ['suppliers', pumpId],
    queryFn: () => supplierApi.list(pumpId),
  })
  const preselected = preselectedSupplierId(suppliers)
  const [supplierId, setSupplierId] = useState<number | null>(preselected)
  const [chosen, setChosen] = useState(false)

  useOnValuesChange([preselected], () => {
    if (!chosen) setSupplierId(preselected)
  })

  const choose = (id: number | null) => {
    setChosen(true)
    setSupplierId(id)
  }

  return { suppliers, supplierId, choose }
}
