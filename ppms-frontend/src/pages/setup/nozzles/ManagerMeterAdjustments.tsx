import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pumpApi } from '../../../api/pumpApi'
import { FormField } from '../../../components/FormField'
import { codeLabel } from '../../../constants/labels'
import { AdjustReadingPanel } from './AdjustReadingPanel'

/** A manager picks a nozzle and asks for a forward meter adjustment, which an Owner or Admin then approves. */
export function ManagerMeterAdjustments({ pumpId }: { pumpId: number }) {
  const { t } = useTranslation('setup')
  const [nozzleId, setNozzleId] = useState<number | null>(null)
  const { data: dus = [] } = useQuery({
    queryKey: ['dus', pumpId],
    queryFn: () => pumpApi.getDUs(pumpId),
  })
  const nozzles = dus.flatMap(du => du.nozzles
    .filter(nozzle => nozzle.status === 'ACTIVE')
    .map(nozzle => ({ du, nozzle })))
  const chosen = nozzles.find(entry => entry.nozzle.id === nozzleId)?.nozzle ?? null

  return (
    <div className="space-y-3">
      <p className="text-sm text-ink-2">{t('nozzles.reading.managerIntro')}</p>
      <FormField label={t('nozzles.reading.chooseNozzle')} className="max-w-sm">
        <select value={nozzleId ?? ''} onChange={e => setNozzleId(e.target.value === '' ? null : Number(e.target.value))}>
          <option value="">{t('nozzles.reading.chooseNozzlePlaceholder')}</option>
          {nozzles.map(({ du, nozzle }) => (
            <option key={nozzle.id} value={nozzle.id}>
              {t('nozzles.reading.nozzleOption', { du: du.name, number: nozzle.nozzleNumber, fuel: codeLabel(nozzle.fuelType) })}
            </option>
          ))}
        </select>
      </FormField>
      {chosen && <AdjustReadingPanel key={chosen.id} pumpId={pumpId} nozzle={chosen} onClose={() => setNozzleId(null)} />}
    </div>
  )
}
