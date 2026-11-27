import { Plus } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { formatIstDateTime } from '../../../utils/date'
import { StatusBadge } from '../../../components/StatusBadge'
import { shiftStatusBadge } from '../../../components/statusBadges'
import { codeLabel } from '../../../constants/labels'
import { formatCurrency } from '../../../utils/format'

// ── Active shift row ──────────────────────────────────────────────────────────

export function ShiftRow({ shift, onClose, onHandOver, onAddCredit, onOperations }: {
  shift: Shift; onClose?: () => void; onHandOver?: () => void; onAddCredit: () => void; onOperations?: () => void
}) {
  const { t } = useTranslation(['shifts', 'common'])
  const startTime = formatIstDateTime(shift.actualStartTime, { hour: '2-digit', minute: '2-digit' })
  const prices = shift.fuelReadings
    .map((r) => `${codeLabel(r.fuelType)} ${formatCurrency(r.priceSnapshot)}`)
    .join(' · ')

  return (
    <tr>
      <td className="px-6 py-3 font-medium text-ink">
        <span className="text-xs text-ink-3 block">
          {shift.duName ?? t('common:duNumber', { number: shift.duNumber })}
        </span>
        {shift.nozzles.map((n) => `#${n.nozzleNumber} ${codeLabel(n.fuelType)}`).join(', ')}
      </td>
      <td className="px-6 py-3 text-ink-2">
        {shift.operatorName}
        {(shift.otherOperators ?? []).some((other) => other.active) && (
          <span className="block text-xs text-ink-3">
            {t('operations.alsoOnShift', {
              names: (shift.otherOperators ?? []).filter((other) => other.active).map((other) => other.operatorName).join(', '),
            })}
          </span>
        )}
        <span className="block md:hidden text-xs text-ink-3">
          {[shift.shiftWindow, t('active.startedTime', { time: startTime }), prices].filter(Boolean).join(' · ')}
        </span>
      </td>
      <td className="px-6 py-3 text-ink-3 hidden md:table-cell">{shift.shiftWindow}</td>
      <td className="px-6 py-3 text-ink-3 hidden md:table-cell">{startTime}</td>
      <td className="px-6 py-3 text-ink-3 text-xs hidden md:table-cell">{prices || '—'}</td>
      <td className="px-6 py-3">
        <StatusBadge {...shiftStatusBadge(shift.status)} />
      </td>
      <td className="px-6 py-3 text-right">
        <div className="flex items-center justify-end gap-2">
          <button
            onClick={onAddCredit}
            className="ui-btn ui-btn-secondary ui-btn-compact"
          >
            <Plus size={12} strokeWidth={2.5} />
            <span>{t('active.credit')}</span>
          </button>
          {onOperations && (
            <button onClick={onOperations} className="ui-btn ui-btn-secondary ui-btn-compact">
              {t('operations.open')}
            </button>
          )}
          {onHandOver && shift.status === 'OPEN' && (
            <button
              onClick={onHandOver}
              className="ui-btn ui-btn-secondary ui-btn-compact"
            >
              {t('active.handOver')}
            </button>
          )}
          {onClose && (
            <button
              onClick={onClose}
              className="ui-btn ui-btn-primary ui-btn-compact"
            >
              {t('common:actions.close')}
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}
