import { useQuery } from '@tanstack/react-query'
import { Ruler } from 'lucide-react'
import { inventoryApi } from '../../api/inventoryApi'
import type { TankStock, InventoryLotDetail } from '../../api/inventoryApi'
import { Modal } from '../../components/Modal'
import { DataTable } from '../../components/DataTable'
import type { DataTableColumn } from '../../components/DataTable'
import { StatusBadge } from '../../components/StatusBadge'
import { LitresText } from '../../components/AmountText'
import { FUEL_COLORS, fmtDate } from './inventoryFormat'

const LOT_COLUMNS: DataTableColumn<InventoryLotDetail>[] = [
  { key: 'date', header: 'In Date', cell: (lot) => fmtDate(lot.deliveryDate) },
  {
    key: 'invoice',
    header: 'Invoice / Bill No',
    cell: (lot) => lot.isDipAdjustment
      ? <StatusBadge tone="warn" icon={Ruler} label="DIP Adj." />
      : (lot.invoiceReference ?? '—'),
  },
  {
    key: 'cost',
    header: 'Cost Price/L',
    numeric: true,
    cell: (lot) => lot.isDipAdjustment ? '—' : `₹${Number(lot.costPricePerUnit).toFixed(4)}`,
  },
  {
    key: 'remaining',
    header: 'Remaining (L)',
    numeric: true,
    cell: (lot) => <LitresText value={Number(lot.remainingQuantity)} tone="ok" className="font-semibold" />,
  },
  {
    key: 'original',
    header: 'Original (L)',
    numeric: true,
    cell: (lot) => <LitresText value={Number(lot.originalQuantity)} className="text-ink-3" />,
  },
]

export function FuelLotsDialog({
  pumpId, tank, onClose,
}: {
  pumpId: number
  tank: TankStock
  onClose: () => void
}) {
  const { data: lots = [], isLoading } = useQuery<InventoryLotDetail[]>({
    queryKey: ['fuelLots', pumpId, tank.tankId],
    queryFn:  () => inventoryApi.getActiveTankLots(pumpId, tank.tankId),
  })

  const cols = FUEL_COLORS[tank.fuelType] ?? FUEL_COLORS.PETROL

  return (
    <Modal
      title={`Stock Lots — ${tank.tankIdentifier}`}
      subtitle={<>Current stock: <LitresText value={tank.currentStock} /></>}
      headerExtra={
        <span className={`mt-1 inline-block text-xs font-bold px-2 py-0.5 rounded-badge ${cols.badge}`}>
          {tank.fuelType}
        </span>
      }
      size="lg"
      onClose={onClose}
      footerClassName="justify-between items-center"
      footer={<>
        <p className="text-xs text-ink-3">
          Lots shown in FIFO order — oldest consumed first
        </p>
        <button
          onClick={onClose}
          className="ui-btn ui-btn-secondary"
        >
          Close
        </button>
      </>}
    >
      {isLoading ? (
        <p className="ui-empty py-8">Loading lots…</p>
      ) : lots.length === 0 ? (
        <p className="ui-empty py-8">
          No active stock lots for this tank. Record a tanker delivery first.
        </p>
      ) : (
        <DataTable
          caption={`Stock lots for ${tank.tankIdentifier}`}
          captionHidden
          columns={LOT_COLUMNS}
          rows={lots}
          rowKey={(lot) => lot.id}
        />
      )}
    </Modal>
  )
}
