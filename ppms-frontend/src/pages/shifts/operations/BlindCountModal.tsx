import { useTranslation } from 'react-i18next'
import type { Shift } from '../../../types/shift'
import { Modal } from '../../../components/Modal'
import { useToastStore } from '../../../store/toastStore'
import { BlindCountForm } from './BlindCountForm'

/** The operator counts the bag and the other collections before the shift is closed. */
export function BlindCountModal({ shift, onClose }: { shift: Shift; onClose: () => void }) {
  const { t } = useTranslation(['shifts', 'common'])
  const { addToast } = useToastStore()

  return (
    <Modal
      title={t('blindCount.title')}
      subtitle={t('drops.subtitle', { du: shift.duName ?? shift.duNumber, operator: shift.operatorName })}
      tone="info"
      size="md"
      fullScreenOnPhone
      onClose={onClose}
      bodyClassName="space-y-4"
    >
      <BlindCountForm shift={shift} onCounted={() => {
        addToast(t('blindCount.saved'), 'success')
        onClose()
      }} />
    </Modal>
  )
}
