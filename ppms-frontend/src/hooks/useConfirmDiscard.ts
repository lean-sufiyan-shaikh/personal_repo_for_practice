import { useCallback } from 'react'

/** Wraps a modal's close action so unsaved entries are not thrown away by a stray Escape or backdrop click. */
export function useConfirmDiscard(hasUnsavedChanges: boolean, onClose: () => void) {
  return useCallback(() => {
    if (hasUnsavedChanges && !window.confirm('Discard the details you have entered?')) return
    onClose()
  }, [hasUnsavedChanges, onClose])
}
