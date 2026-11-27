import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { Modal } from '../../../components/Modal'
import { SkeletonRows } from '../../../components/Skeleton'
import { canPerformAction } from '../../../permissions/permissions'
import { useAuthStore } from '../../../store/authStore'
import { parseApiError } from '../../../utils/apiError'
import { ReliefPanel } from './ReliefPanel'
import { ShiftOperatorsPanel } from './ShiftOperatorsPanel'
import { ShiftTimelineList } from './ShiftTimelineList'
import { SwapNozzleForm } from './SwapNozzleForm'
import { useShiftOperations } from './useShiftOperations'

/**
 * Work on an open shift without closing it: who is on it, moving a nozzle to another operator, and breaks covered by a
 * relief operator. A manager can do all of it; an operator only starts and ends their own break.
 */
export function ShiftOperationsModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  const user = useAuthStore((state) => state.user)
  const canManage = canPerformAction('shift:manage-operators', user?.role)
  const { data, isLoading, error, refreshAfterChange } = useShiftOperations(shift.pumpId, shift.id)

  return (
    <Modal
      title={t('operations.title')}
      subtitle={t('drops.subtitle', { du: shift.duName ?? shift.duNumber, operator: shift.operatorName })}
      tone="info"
      size="lg"
      fullScreenOnPhone
      onClose={onClose}
      bodyClassName="space-y-6"
    >
      {isLoading && <SkeletonRows count={3} />}
      {error && <p role="alert" className="ui-alert ui-alert-danger">{parseApiError(error, t('operations.loadFailed'))}</p>}
      {data && (
        <>
          <ShiftOperatorsPanel shift={shift} operators={data.operators} canManage={canManage} onChanged={refreshAfterChange} />
          {canManage && <SwapNozzleForm shift={shift} operators={data.operators} onChanged={refreshAfterChange} />}
          <ReliefPanel shift={shift} operators={data.operators} reliefs={data.reliefs}
            ownOperatorId={canManage ? null : user?.userId ?? null} onChanged={refreshAfterChange} />
          <ShiftTimelineList events={data.timeline} />
        </>
      )}
    </Modal>
  )
}
