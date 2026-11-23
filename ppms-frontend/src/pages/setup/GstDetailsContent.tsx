import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../utils/apiError'
import { pumpApi } from '../../api/pumpApi'
import type { PumpSummary } from '../../api/pumpApi'
import { useToastStore } from '../../store/toastStore'
import { isValidGstin, isValidInvoicePrefix, normaliseGstText } from '../../utils/gst'

// ── GST details used on credit invoices ──────────────────────────────────────

export function GstDetailsContent({ pump, onUpdated }: { pump: PumpSummary; onUpdated: () => void }) {
  const { t } = useTranslation('setup')
  const { addToast } = useToastStore()
  const [gstin, setGstin]                 = useState(pump.gstin ?? '')
  const [legalName, setLegalName]         = useState(pump.legalName ?? '')
  const [stateName, setStateName]         = useState(pump.stateName ?? '')
  const [invoicePrefix, setInvoicePrefix] = useState(pump.invoicePrefix ?? '')
  const [error, setError]                 = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: () => pumpApi.updatePumpSettings(pump.id, {
      gstin: normaliseGstText(gstin),
      legalName: legalName.trim(),
      stateName: stateName.trim(),
      invoicePrefix: normaliseGstText(invoicePrefix),
    }),
    onSuccess: () => {
      addToast(t('gst.saved'), 'success')
      setError(null)
      onUpdated()
    },
    onError: (err) => setError(parseApiError(err, t('gst.saveFailed'))),
  })

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault()
    if (!isValidGstin(gstin)) { setError(t('gst.gstinFormat')); return }
    if (!isValidInvoicePrefix(invoicePrefix)) { setError(t('gst.prefixFormat')); return }
    setError(null)
    mutation.mutate()
  }

  return (
    <form onSubmit={handleSave} className="space-y-4 pt-2">
      <div className="ui-alert bg-info-bg border border-info rounded-control p-3 text-xs text-info">
        {t('gst.introBefore')}
        {' '}<strong>{(normaliseGstText(invoicePrefix) || 'PMP3')}/26-27/0001</strong> {t('gst.introAfter')}
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <label className="block">
          <span className="ui-label">GSTIN</span>
          <input value={gstin} onChange={(e) => setGstin(e.target.value.toUpperCase())}
            maxLength={15} autoCapitalize="characters" placeholder={t('gst.gstinPlaceholder')} className="text-sm" />
        </label>
        <label className="block">
          <span className="ui-label">{t('gst.legalName')}</span>
          <input value={legalName} onChange={(e) => setLegalName(e.target.value)}
            maxLength={150} placeholder={t('gst.legalNamePlaceholder')} className="text-sm" />
        </label>
        <label className="block">
          <span className="ui-label">{t('gst.state')}</span>
          <input value={stateName} onChange={(e) => setStateName(e.target.value)}
            maxLength={60} placeholder={t('gst.statePlaceholder')} className="text-sm" />
        </label>
        <label className="block">
          <span className="ui-label">{t('gst.invoicePrefix')}</span>
          <input value={invoicePrefix} onChange={(e) => setInvoicePrefix(e.target.value.toUpperCase())}
            maxLength={5} autoCapitalize="characters" placeholder={t('gst.invoicePrefixPlaceholder')} className="text-sm" />
        </label>
      </div>

      {error && <p role="alert" className="ui-error-text">{error}</p>}

      <button type="submit" disabled={mutation.isPending} className="ui-btn ui-btn-primary">
        {mutation.isPending ? t('actions.saving') : t('gst.save')}
      </button>
    </form>
  )
}
