import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Modal } from './Modal'
import { Spinner } from './Spinner'

interface RejectReasonDialogProps {
  title: string
  subtitle: string
  isPending: boolean
  error: string | null
  onConfirm: (reason: string) => void
  onClose: () => void
}

/** Asks why something is being rejected; the reason is required and is saved with the rejection. */
export function RejectReasonDialog({ title, subtitle, isPending, error, onConfirm, onClose }: RejectReasonDialogProps) {
  const { t } = useTranslation()
  const [reason, setReason] = useState('')
  const [missingReason, setMissingReason] = useState(false)

  const confirm = () => {
    if (!reason.trim()) {
      setMissingReason(true)
      return
    }
    setMissingReason(false)
    onConfirm(reason.trim())
  }

  const shownError = missingReason ? t('rejectDialog.missingReason') : error

  return (
    <Modal
      title={title}
      subtitle={subtitle}
      tone="danger"
      onClose={onClose}
      canClose={!isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={isPending} className="ui-btn ui-btn-secondary">{t('actions.cancel')}</button>
          <button type="button" onClick={confirm} disabled={isPending} className="ui-btn ui-btn-danger">
            {isPending ? <span className="flex items-center gap-1.5"><Spinner />{t('actions.rejecting')}</span> : t('actions.reject')}
          </button>
        </>
      }
    >
      <label className="block">
        <span className="ui-label">{t('rejectDialog.reason')} <span aria-hidden="true" className="text-bad">*</span></span>
        <textarea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          rows={3}
          maxLength={500}
          required
          aria-invalid={missingReason}
          placeholder={t('rejectDialog.placeholder')}
          className="resize-none text-sm"
          data-autofocus
        />
      </label>
      {shownError && <div role="alert" className="ui-alert ui-alert-danger text-sm">{shownError}</div>}
    </Modal>
  )
}
