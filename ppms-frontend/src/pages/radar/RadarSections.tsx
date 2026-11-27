import type { ReactNode } from 'react'
import { TrendingDown, TrendingUp } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { NozzleScore, PersonScore, TankScore } from '../../api/radarApi'
import { StatusBadge } from '../../components/StatusBadge'
import { PageSection } from '../../components/layout/PageSection'
import { codeLabel } from '../../constants/labels'
import { formatDecimal, formatCurrency } from '../../utils/format'
import { RadarScoreCard } from './RadarScoreCard'

function ScoreList({ title, hint, empty, children }: { title: string; hint: string; empty: string; children: ReactNode[] }) {
  return (
    <PageSection title={title} description={hint}>
      {children.length === 0
        ? <p className="text-sm text-ink-3">{empty}</p>
        : <div className="space-y-3">{children}</div>}
    </PageSection>
  )
}

function TrendBadge({ trend }: { trend: PersonScore['trend'] }) {
  const { t } = useTranslation('radar')
  if (trend === 'STEADY') return null
  return trend === 'RISING'
    ? <StatusBadge tone="warn" icon={TrendingUp} label={t('card.trend.RISING')} />
    : <StatusBadge tone="info" icon={TrendingDown} label={t('card.trend.FALLING')} />
}

export function PeopleSection({ people }: { people: PersonScore[] }) {
  const { t } = useTranslation('radar')
  return (
    <ScoreList title={t('sections.people')} hint={t('sections.peopleHint')} empty={t('sections.emptyPeople')}>
      {people.map(person => (
        <RadarScoreCard
          key={person.userId}
          title={person.name}
          facts={t('card.personFacts', {
            role: codeLabel(person.role), shifts: person.shiftCount, shortShifts: person.shortShiftCount,
            amount: formatCurrency(person.shortAmount), corrections: person.correctionCount,
          })}
          extraBadge={<TrendBadge trend={person.trend} />}
          score={person.score}
          band={person.band}
          reasons={person.reasons}
          evidence={person.evidence}
        />
      ))}
    </ScoreList>
  )
}

export function NozzlesSection({ nozzles }: { nozzles: NozzleScore[] }) {
  const { t } = useTranslation('radar')
  return (
    <ScoreList title={t('sections.nozzles')} hint={t('sections.nozzlesHint')} empty={t('sections.emptyNozzles')}>
      {nozzles.map(nozzle => (
        <RadarScoreCard
          key={nozzle.nozzleId}
          title={t('card.nozzleName', { du: nozzle.duName, number: nozzle.nozzleNumber })}
          facts={t('card.nozzleFacts', {
            fuel: codeLabel(nozzle.fuelType), shifts: nozzle.shiftCount, amendments: nozzle.amendmentCount,
            jumps: nozzle.readingJumpCount, outliers: nozzle.salesOutlierCount,
          })}
          score={nozzle.score}
          band={nozzle.band}
          reasons={nozzle.reasons}
          evidence={nozzle.evidence}
        />
      ))}
    </ScoreList>
  )
}

export function TanksSection({ tanks }: { tanks: TankScore[] }) {
  const { t } = useTranslation('radar')
  return (
    <ScoreList title={t('sections.tanks')} hint={t('sections.tanksHint')} empty={t('sections.emptyTanks')}>
      {tanks.map(tank => (
        <RadarScoreCard
          key={tank.tankId}
          title={tank.tankIdentifier}
          facts={t('card.tankFacts', {
            fuel: codeLabel(tank.fuelType), weeks: tank.weeks.length, limit: formatDecimal(tank.lossAlertPercent),
          })}
          score={tank.score}
          band={tank.band}
          reasons={tank.reasons}
          evidence={tank.evidence}
        />
      ))}
    </ScoreList>
  )
}
