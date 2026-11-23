import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ancillaryApi } from '../../api/ancillaryApi'
import type { AncillaryProduct } from '../../api/ancillaryApi'
import { Spinner } from '../../components/Spinner'
import { Modal } from '../../components/Modal'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { isValidHsnCode } from '../../utils/gst'

export function EditHsnDialog({
  pumpId, product, onClose, onSuccess,
}: {
  pumpId: number
  product: AncillaryProduct
  onClose: () => void
  onSuccess: () => void
}) {
  const { addToast } = useToastStore()
  const [hsnCode, setHsnCode] = useState(product.hsnCode ?? '')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => ancillaryApi.updateProduct(pumpId, product.id, { hsnCode: hsnCode.trim() }),
    onSuccess: () => {
      addToast('HSN code saved', 'success')
      onSuccess()
    },
    onError: (err) => setError(parseApiError(err, 'Failed to save HSN code')),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!isValidHsnCode(hsnCode)) { setError('HSN code must be 4 to 8 digits'); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <Modal
      title="HSN Code"
      subtitle={product.displayName}
      tone="info"
      size="sm"
      onClose={onClose}
      canClose={!mutation.isPending}
      closeOnBackdropClick
      onSubmit={handleSubmit}
      bodyClassName="space-y-3"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">Cancel</button>
        <button type="submit" disabled={mutation.isPending} className="ui-btn ui-btn-primary">
          {mutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />Saving…</span> : 'Save'}
        </button>
      </>}
    >
      <label className="block">
        <span className="ui-label">HSN Code</span>
        <input
          className="text-sm"
          inputMode="numeric"
          maxLength={8}
          value={hsnCode}
          onChange={e => setHsnCode(e.target.value.replace(/\D/g, ''))}
          placeholder="e.g. 27101980 — leave blank to remove"
          data-autofocus
        />
      </label>
      <p className="text-xs text-ink-3">Printed on tax invoices for credit sales of this product.</p>
      {error && <div role="alert" className="ui-alert ui-alert-danger text-sm">{error}</div>}
    </Modal>
  )
}
