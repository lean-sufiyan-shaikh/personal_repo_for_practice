import type { DataTableColumn } from '../../../components/DataTable'
import type { Shift } from '../../../types/shift'

/** Headers matching the cells HistoryRow renders. */
export const HISTORY_COLUMNS: DataTableColumn<Shift>[] = [
  { key: 'id', header: 'ID', className: 'px-5 hidden md:table-cell' },
  { key: 'nozzle', header: 'Nozzle', className: 'px-5' },
  { key: 'window', header: 'Shift', className: 'px-5 hidden md:table-cell' },
  { key: 'operator', header: 'Operator', className: 'px-5' },
  { key: 'date', header: 'Date', className: 'px-5' },
  { key: 'amountDue', header: 'Amount Due', className: 'px-5' },
  { key: 'status', header: 'Status', className: 'px-5' },
  { key: 'discrepancy', header: 'Discrepancy', className: 'px-5 hidden md:table-cell' },
  { key: 'actions', header: <span className="sr-only">Actions</span>, className: 'px-4' },
]
