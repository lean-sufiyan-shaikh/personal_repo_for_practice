import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { parseApiError } from '../../../utils/apiError'
import { userApi } from '../../../api/userApi'
import type { StaffMember } from '../../../api/userApi'
import { Spinner } from '../../../components/Spinner'

interface Props {
  pumpId: number
  member: StaffMember
  onClose: () => void
}

const rateText = (rate: number | null | undefined) => (rate != null ? String(rate) : '')

export function PayRatesPanel({ pumpId, member, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const queryClient = useQueryClient()
  const [rateShift1, setRateShift1]       = useState(rateText(member.shift1HourlyRate))
  const [rateStandard, setRateStandard]   = useState(rateText(member.standardHourlyRate))
  const [rateDaily, setRateDaily]         = useState(rateText(member.dailyRate))
  const [rateError, setRateError]         = useState<string | null>(null)

  const rateMutation = useMutation({
    mutationFn: ({ userId, shift1HourlyRate, standardHourlyRate, dailyRate }: { userId: number; shift1HourlyRate?: number; standardHourlyRate?: number; dailyRate?: number }) =>
      userApi.updatePayRates(userId, { shift1HourlyRate, standardHourlyRate, dailyRate }),
    onSuccess: () => {
      setRateError(null)
      onClose()
      queryClient.invalidateQueries({ queryKey: ['staff', pumpId] })
    },
    onError: (err) => setRateError(parseApiError(err, t('staff.rates.failed'))),
  })

  return (
    <div className="px-3 py-3 border-t border-border">
      <div className="max-w-sm space-y-3">
        {member.role === 'OPERATOR' ? (
          <>
            <div>
              <p className="text-xs font-semibold text-ink">{t('staff.rates.hourlyTitle', { name: member.fullName })}</p>
              <p className="text-xs text-ink-3 mt-0.5">
                {t('staff.rates.hourlyHint')}
              </p>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <span className="ui-label">
                  {t('staff.rates.night')} <span className="text-bad">*</span>
                </span>
                <div className="relative">
                  <span className="absolute left-2 top-1/2 -translate-y-1/2 text-xs text-ink-3">₹</span>
                  <input aria-label={t('staff.rates.night')} type="number" min="1" step="0.01" value={rateShift1}
                    onChange={e => setRateShift1(e.target.value)} placeholder={t('example', { value: '60' })}
                    className="w-full pl-5 pr-2 text-xs min-h-10" />
                </div>
              </div>
              <div>
                <span className="ui-label">
                  {t('staff.rates.day')} <span className="text-bad">*</span>
                </span>
                <div className="relative">
                  <span className="absolute left-2 top-1/2 -translate-y-1/2 text-xs text-ink-3">₹</span>
                  <input aria-label={t('staff.rates.day')} type="number" min="1" step="0.01" value={rateStandard}
                    onChange={e => setRateStandard(e.target.value)} placeholder={t('example', { value: '45' })}
                    className="w-full pl-5 pr-2 text-xs min-h-10" />
                </div>
              </div>
            </div>
            {rateError && <p role="alert" className="ui-error-text">{rateError}</p>}
            <div className="flex gap-2">
              <button type="button"
                disabled={!rateShift1 || !rateStandard || rateMutation.isPending}
                onClick={() => rateMutation.mutate({ userId: member.id, shift1HourlyRate: parseFloat(rateShift1), standardHourlyRate: parseFloat(rateStandard) })}
                className="ui-btn ui-btn-primary ui-btn-compact">
                {rateMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />{t('common:actions.saving')}</span> : t('staff.rates.saveRates')}
              </button>
              <button type="button" onClick={onClose}
                className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">{t('common:actions.cancel')}</button>
            </div>
          </>
        ) : (
          <>
            <div>
              <p className="text-xs font-semibold text-ink">{t('staff.rates.dailyTitle', { name: member.fullName })}</p>
              <p className="text-xs text-ink-3 mt-0.5">
                {t('staff.rates.dailyHint')}
              </p>
            </div>
            <div>
              <span className="ui-label">
                {t('staff.rates.daily')} <span className="text-bad">*</span>
              </span>
              <div className="relative max-w-40">
                <span className="absolute left-2 top-1/2 -translate-y-1/2 text-xs text-ink-3">₹</span>
                <input aria-label={t('staff.rates.daily')} type="number" min="1" step="0.01" value={rateDaily}
                  onChange={e => setRateDaily(e.target.value)} placeholder={t('example', { value: '800' })}
                  className="w-full pl-5 pr-2 text-xs min-h-10" />
              </div>
            </div>
            {rateError && <p role="alert" className="ui-error-text">{rateError}</p>}
            <div className="flex gap-2">
              <button type="button"
                disabled={!rateDaily || rateMutation.isPending}
                onClick={() => rateMutation.mutate({ userId: member.id, dailyRate: parseFloat(rateDaily) })}
                className="ui-btn ui-btn-primary ui-btn-compact">
                {rateMutation.isPending ? <span className="flex items-center gap-1"><Spinner className="w-3 h-3" />{t('common:actions.saving')}</span> : t('staff.rates.saveRate')}
              </button>
              <button type="button" onClick={onClose}
                className="ui-btn ui-btn-secondary min-h-0 px-3 py-1.5 text-xs">{t('common:actions.cancel')}</button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
