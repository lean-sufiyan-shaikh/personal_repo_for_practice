import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary } from '../../api/pumpApi'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'

// ── Create pump form ──────────────────────────────────────────────────────────

export function CreatePumpForm({ onCreated }: { onCreated: (p: PumpSummary) => void }) {
  const { t } = useTranslation('setup')
  const { addToast } = useToastStore()
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [maxDUs, setMaxDUs] = useState('4')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: pumpApi.createPump,
    onSuccess: (pump) => {
      setName('')
      setAddress('')
      setMaxDUs('4')
      setError(null)
      addToast(t('createPump.created', { name: pump.name }), 'success')
      onCreated(pump)
    },
    onError: (err) => {
      const msg = parseApiError(err, t('createPump.failed'))
      setError(msg)
      addToast(msg, 'error')
    },
  })

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    mutation.mutate({ name, address, maxDuCount: Number(maxDUs) })
  }

  return (
    <form onSubmit={submit} className="space-y-3 pt-2">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block">
            <span className="ui-label">{t('createPump.name')}</span>
            <input
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('createPump.namePlaceholder')}
              className="text-sm"
            />
          </label>
        </div>
        <div>
          <label className="block">
            <span className="ui-label">{t('createPump.maxDus')}</span>
            <input
              required
              type="number"
              min="1"
              max="20"
              value={maxDUs}
              onChange={(e) => setMaxDUs(e.target.value)}
              className="text-sm"
            />
          </label>
        </div>
      </div>
      <div>
        <label className="block">
          <span className="ui-label">{t('createPump.address')}</span>
          <input
            required
            value={address}
            onChange={(e) => setAddress(e.target.value)}
            placeholder={t('createPump.addressPlaceholder')}
            className="text-sm"
          />
        </label>
      </div>
      {error && <p role="alert" className="ui-error-text">{error}</p>}
      <button
        type="submit"
        disabled={mutation.isPending}
        className="ui-btn ui-btn-primary"
      >
        {mutation.isPending
        ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('createPump.creating')}</span>
        : t('createPump.submit')}
      </button>
    </form>
  )
}
