import { useTranslation } from 'react-i18next'
import { Modal } from './Modal'

interface DiscardChangesDialogProps {
  onDiscard: () => void
  onKeepEditing: () => void
}

/** In-app replacement for window.confirm when a form with entered details is about to be closed. */
export function DiscardChangesDialog({ onDiscard, onKeepEditing }: DiscardChangesDialogProps) {
  const { t } = useTranslation()
  return (
    <Modal
      title={t('discardDialog.title')}
      size="sm"
      onClose={onKeepEditing}
      footer={
        <>
          <button type="button" data-autofocus onClick={onKeepEditing} className="ui-btn ui-btn-secondary">{t('actions.keepEditing')}</button>
          <button type="button" onClick={onDiscard} className="ui-btn ui-btn-danger">{t('actions.discard')}</button>
        </>
      }
    >
      <p className="text-sm text-ink-2">{t('discardDialog.body')}</p>
    </Modal>
  )
}
