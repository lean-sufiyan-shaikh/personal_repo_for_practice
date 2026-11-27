import { useTranslation } from 'react-i18next'
import { PageSection } from '../../components/layout/PageSection'

/** The scoring explained in plain words, so a score is never a black box. */
export function RadarHowItWorks({ minimumShifts }: { minimumShifts: number }) {
  const { t } = useTranslation('radar')
  const points = [
    t('howItWorks.bands'),
    t('howItWorks.people'),
    t('howItWorks.nozzles'),
    t('howItWorks.tanks'),
    t('howItWorks.notEnoughData', { minimum: minimumShifts }),
    t('howItWorks.innocentReasons'),
    t('howItWorks.weekly'),
  ]
  return (
    <PageSection title={t('howItWorks.title')} description={t('howItWorks.summary')}>
      <ul className="list-disc space-y-1 pl-5 text-sm text-ink-2">
        {points.map(point => <li key={point}>{point}</li>)}
      </ul>
    </PageSection>
  )
}
