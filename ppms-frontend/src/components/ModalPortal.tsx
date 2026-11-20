import { createPortal } from 'react-dom'
import { useEffect, useId, useLayoutEffect, useRef } from 'react'
import type { ReactNode } from 'react'

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

const openDialogs: HTMLElement[] = []

function isTopDialog(dialog: HTMLElement) {
  return openDialogs[openDialogs.length - 1] === dialog
}

function focusableElements(container: HTMLElement) {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    .filter((element) => !element.closest('[aria-hidden="true"]') && element.getClientRects().length > 0)
}

interface ModalPortalProps {
  children: ReactNode
  /** Called on Escape. Leave undefined while the modal must not be dismissed (e.g. a save is in flight). */
  onClose?: () => void
}

/**
 * Renders a modal into document.body (escaping ancestor stacking contexts) as an accessible dialog:
 * named by its `.ui-modal-title`, focus moved inside and trapped, Escape closes, and focus returns to the opener.
 */
export function ModalPortal({ children, onClose }: ModalPortalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const generatedTitleId = useId()
  const onCloseRef = useRef(onClose)

  useEffect(() => {
    onCloseRef.current = onClose
  }, [onClose])

  useLayoutEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    openDialogs.push(dialog)

    const title = dialog.querySelector<HTMLElement>('.ui-modal-title')
    if (title) {
      if (!title.id) title.id = generatedTitleId
      dialog.setAttribute('aria-labelledby', title.id)
    }

    if (!dialog.contains(document.activeElement)) {
      const preferred = dialog.querySelector<HTMLElement>('[data-autofocus]')
      const firstField = focusableElements(dialog).find((element) => !element.classList.contains('ui-modal-close'))
      ;(preferred ?? firstField ?? dialog).focus()
    }

    return () => {
      const index = openDialogs.indexOf(dialog)
      if (index !== -1) openDialogs.splice(index, 1)
      if (opener && document.contains(opener)) opener.focus()
    }
  }, [generatedTitleId])

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!isTopDialog(dialog)) return
      if (event.key === 'Escape') {
        if (!event.defaultPrevented && onCloseRef.current) {
          event.preventDefault()
          onCloseRef.current()
        }
        return
      }
      if (event.key !== 'Tab' || event.defaultPrevented) return
      const focusable = focusableElements(dialog)
      if (focusable.length === 0) {
        event.preventDefault()
        dialog.focus()
        return
      }
      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const active = document.activeElement
      if (event.shiftKey && (active === first || !dialog.contains(active))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [])

  return createPortal(
    <div ref={dialogRef} role="dialog" aria-modal="true" tabIndex={-1} className="outline-none">
      {children}
    </div>,
    document.body,
  )
}
