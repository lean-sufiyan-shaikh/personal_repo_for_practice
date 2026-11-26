import type { TFunction } from 'i18next'
import type { ReceiptNozzle, TankStock } from '../../../api/inventoryApi'
import { formatDecimal } from '../../../utils/format'
import type { DeliveryRow, PreparedDeliveryItem } from './deliveryRows'
import { densityDifference, isDensityFlagged, receiptResult, toReceiptCheckRequest } from './receiptCheck'
import type { ReceiptTolerances } from './receiptCheck'

interface Input {
  date: string
  invoice: string
  rows: DeliveryRow[]
  tanks: TankStock[]
  isOwnerOrAdmin: boolean
  nozzlesForTank: (tankId: number) => ReceiptNozzle[]
  tolerances: ReceiptTolerances
  t: TFunction<['inventory', 'common']>
}

export type PreparedDelivery = { items: PreparedDeliveryItem[]; error: null } | { items: null; error: string }

export function prepareDeliveryItems({ date, invoice, rows, tanks, isOwnerOrAdmin, nozzlesForTank, tolerances, t }: Input): PreparedDelivery {
  if (!date || !invoice.trim()) return { items: null, error: t('recordDelivery.dateAndInvoiceRequired') }

  const tankIds = rows.map(r => r.tankId)
  if (new Set(tankIds).size !== tankIds.length) return { items: null, error: t('recordDelivery.duplicateTank') }

  const items: PreparedDeliveryItem[] = []
  for (const r of rows) {
    if (!r.tankId) return { items: null, error: t('recordDelivery.selectTankEveryRow') }
    const tank = tanks.find(tk => tk.tankId === Number(r.tankId))
    if (!tank) return { items: null, error: t('recordDelivery.tankNotFound') }
    const quantity = parseFloat(r.qty)
    if (!r.qty || isNaN(quantity) || quantity <= 0) return { items: null, error: t('recordDelivery.quantityEveryRow') }
    if (tank.capacity > 0 && quantity > tank.capacity - tank.currentStock) {
      return {
        items: null,
        error: t('recordDelivery.exceedsSpace', {
          tank: tank.tankIdentifier, quantity: formatDecimal(quantity), space: formatDecimal(tank.capacity - tank.currentStock),
        }),
      }
    }
    const cost = isOwnerOrAdmin ? parseFloat(r.costPrice) : 0
    if (isOwnerOrAdmin && (!r.costPrice || isNaN(cost) || cost <= 0)) return { items: null, error: t('recordDelivery.costEveryRow') }

    const nozzles = nozzlesForTank(tank.tankId)
    const receipt = receiptResult(r.check, nozzles, quantity, tolerances.receiptTolerancePercent)
    if (!receipt) return { items: null, error: t('receipt.completeEveryRow') }
    const difference = densityDifference(r.check)
    items.push({
      tankId: tank.tankId,
      tankIdentifier: tank.tankIdentifier,
      fuelType: tank.fuelType,
      quantityDelivered: quantity,
      costPricePerUnit: cost,
      totalCost: isOwnerOrAdmin ? quantity * cost : 0,
      stockAfter: tank.currentStock + receipt.receivedLitres,
      capacity: tank.capacity,
      receiptCheck: toReceiptCheckRequest(r.check, nozzles),
      receipt,
      densityDifference: difference,
      densityFlagged: isDensityFlagged(difference, tolerances.densityTolerance),
    })
  }
  return { items, error: null }
}
