import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { pumpApi } from '../../api/pumpApi'
import { usePumpStore } from '../../store/usePumpStore'
import { Reveal } from '../../components/Reveal'
import { CalibrationForm } from './CalibrationForm'
import { CalibrationHistory } from './CalibrationHistory'
import { useCalibrationHistory } from './useCalibrationHistory'

export default function CalibrationPage() {
  const { t } = useTranslation('calibration')
  const { selectedPumpId: pumpId } = usePumpStore()

  const { data: dus = [], error: dusError } = useQuery({
    queryKey:  ['dus', pumpId],
    queryFn:   () => pumpApi.getDUs(pumpId!),
    enabled:   !!pumpId,
  })
  const nozzles = dus.flatMap((d) => d.nozzles)

  const history = useCalibrationHistory(pumpId)
  const { calibrationsPage, calibrations, calibrationsError } = history

  const showHistoryFor = (nozzleId: number) => {
    history.setHistoryNozzleId(nozzleId)
    history.setHistoryPage(0)
  }

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <Reveal delay={60}>
      <div className="ui-section-hero">
        <div>
          <p className="ui-section-kicker">{t('page.kicker')}</p>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle">{t('page.subtitle')}</p>
        </div>
        <div className="ui-section-meta">
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.nozzles')}</span>
            <span className="ui-section-meta-value">{nozzles.length}</span>
          </div>
          <div className="ui-section-meta-pill">
            <span className="ui-section-meta-label">{t('page.history')}</span>
            <span className="ui-section-meta-value">{calibrationsPage?.totalElements ?? calibrations?.length ?? 0}</span>
          </div>
        </div>
      </div>
      </Reveal>

      {(dusError || calibrationsError) && (
        <div className="ui-alert ui-alert-warning text-sm">
          {t('page.loadFailed')}
        </div>
      )}

      <CalibrationForm pumpId={pumpId} nozzles={nozzles} onRecorded={showHistoryFor} />

      <CalibrationHistory history={history} nozzles={nozzles} />
    </div>
  )
}
