import { create } from 'zustand'

export type ToastVariant = 'success' | 'error' | 'warning' | 'info'

/** A link inside the toast to the page that fixes what it reports. */
export interface ToastAction {
  label: string
  to: string
}

export interface Toast {
  id: number
  message: string
  variant: ToastVariant
  action?: ToastAction
}

interface ToastState {
  toasts: Toast[]
  addToast: (message: string, variant?: ToastVariant, action?: ToastAction) => void
  removeToast: (id: number) => void
}

const TOAST_MS = 4000
// A toast with a link stays long enough to read it and reach for the link.
const TOAST_WITH_ACTION_MS = 10000

let nextId = 0

export const useToastStore = create<ToastState>(set => ({
  toasts: [],
  addToast: (message, variant = 'success', action) => {
    const id = ++nextId
    set(s => ({ toasts: [...s.toasts, { id, message, variant, action }] }))
    setTimeout(() => {
      set(s => ({ toasts: s.toasts.filter(t => t.id !== id) }))
    }, action ? TOAST_WITH_ACTION_MS : TOAST_MS)
  },
  removeToast: (id) => set(s => ({ toasts: s.toasts.filter(t => t.id !== id) })),
}))
