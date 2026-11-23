import { Modal } from './Modal'

interface DiscardChangesDialogProps {
  onDiscard: () => void
  onKeepEditing: () => void
}

/** In-app replacement for window.confirm when a form with entered details is about to be closed. */
export function DiscardChangesDialog({ onDiscard, onKeepEditing }: DiscardChangesDialogProps) {
  return (
    <Modal
      title="Discard what you entered?"
      size="sm"
      onClose={onKeepEditing}
      footer={
        <>
          <button type="button" data-autofocus onClick={onKeepEditing} className="ui-btn ui-btn-secondary">Keep editing</button>
          <button type="button" onClick={onDiscard} className="ui-btn ui-btn-danger">Discard</button>
        </>
      }
    >
      <p className="text-sm text-ink-2">The details you have typed in this form will be lost.</p>
    </Modal>
  )
}
