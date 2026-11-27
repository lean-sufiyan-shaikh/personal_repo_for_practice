import { useState } from 'react'
import type { FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Modal } from '../../../components/Modal'
import type { ModalTone } from '../../../components/Modal'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'

interface Props {
  title: string
  subtitle: string
  label: string
  missingText: string
  submitLabel: string
  submittingLabel: string
  tone: ModalTone
  maxLength: number
  isPending: boolean
  error: string | null
  onSubmit: (text: string) => void
  onClose: () => void
}

/** Asks for one required piece of text: a question to the manager, a reply, or the reason for re-opening a day. */
export function DayTextDialog({ title, subtitle, label, missingText, submitLabel, submittingLabel, tone, maxLength, isPending, error, onSubmit, onClose }: Props) {
  const { t } = useTranslation()
  const [text, setText] = useState('')
  const [missing, setMissing] = useState(false)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!text.trim()) {
      setMissing(true)
      return
    }
    onSubmit(text.trim())
  }

  return (
    <Modal title={title} subtitle={subtitle} tone={tone} size="md" fullScreenOnPhone onClose={onClose} canClose={!isPending}
      onSubmit={submit} bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={isPending} className="ui-btn ui-btn-secondary min-h-11">{t('actions.cancel')}</button>
          <LoadingButton type="submit" isLoading={isPending} loadingLabel={submittingLabel}
            className={`${tone === 'warning' ? 'ui-btn-danger' : 'ui-btn-primary'} min-h-11`}>
            {submitLabel}
          </LoadingButton>
        </>
      }
    >
      <FormField label={label} error={missing ? missingText : undefined}>
        <textarea value={text} rows={4} maxLength={maxLength} aria-required="true" className="resize-none" data-autofocus
          onChange={(event) => { setText(event.target.value); if (event.target.value.trim()) setMissing(false) }} />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
