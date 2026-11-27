import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { radarApi } from '../../api/radarApi'
import type { RadarDays } from '../../api/radarApi'
import { LoadError } from '../../components/LoadError'
import { SkeletonRows } from '../../components/Skeleton'
import { Page } from '../../components/layout/Page'
import { PageHeader } from '../../components/layout/PageHeader'
import { useUrlParam } from '../../components/layout/useUrlParam'
import { usePumpStore } from '../../store/usePumpStore'
import { formatIstDate } from '../../utils/date'
import { RadarHowItWorks } from './RadarHowItWorks'
import { NozzlesSection, PeopleSection, TanksSection } from './RadarSections'

const PERIODS: RadarDays[] = [30, 90]

/** Owner/Admin only: rule-based "worth a look" scores per person, nozzle and tank, with the evidence behind each. */
export default function RadarPage() {
  const { t } = useTranslation('radar')
  const { selectedPumpId: pumpId } = usePumpStore()
  const daysParam = useUrlParam('days')
  const days: RadarDays = daysParam.value === '90' ? 90 : 30

  const { data, isLoading, isLoadingError, refetch } = useQuery({
    queryKey: ['leakageRadar', pumpId, days],
    queryFn: () => radarApi.get(pumpId!, days),
    enabled: !!pumpId,
    placeholderData: keepPreviousData,
  })

  const periodChips = (
    <div className="ui-chip-row" role="group" aria-label={t('page.periodLabel')}>
      {PERIODS.map(period => (
        <button
          key={period}
          type="button"
          className="ui-chip"
          aria-pressed={days === period}
          onClick={() => daysParam.set(String(period))}
        >
          {t(period === 30 ? 'page.days30' : 'page.days90')}
        </button>
      ))}
    </div>
  )

  return (
    <Page>
      <PageHeader
        title={t('page.title')}
        description={data
          ? t('page.description', { from: formatIstDate(data.from), to: formatIstDate(data.to) })
          : undefined}
        secondaryActions={periodChips}
      />
      {isLoadingError && <LoadError message={t('page.loadError')} onRetry={() => void refetch()} />}
      {isLoading && <SkeletonRows count={6} />}
      {data && (
        <>
          <PeopleSection people={data.people} />
          <NozzlesSection nozzles={data.nozzles} />
          <TanksSection tanks={data.tanks} />
          <RadarHowItWorks minimumShifts={data.minimumShifts} />
        </>
      )}
    </Page>
  )
}
