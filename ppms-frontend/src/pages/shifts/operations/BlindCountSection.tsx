import { useTranslation } from 'react-i18next'
import type { BlindCount } from '../../../types/shift'
import { MoneyText } from '../../../components/AmountText'
import { Section } from '../close/CloseShiftFields'
import { BlindCountSummary } from './BlindCountSummary'

/** In the close form, the payments section once the collections were counted blind: shown, never edited. */
export function BlindCountSection({ count, creditTotal }: { count: BlindCount; creditTotal: number }) {
  const { t } = useTranslation(['shifts', 'common'])
  return (
    <Section label="3" title={t('close.sections.payment')}>
      <p className="mb-2 text-sm text-ink-2">{t('blindCount.usedAtClose')}</p>
      <BlindCountSummary count={count} />
      {creditTotal > 0 && (
        <p className="mt-3 flex items-center justify-between gap-3 text-sm">
          <span className="text-ink-2">{t('close.payment.creditTotal')}</span>
          <MoneyText value={creditTotal} className="font-semibold text-ink" />
        </p>
      )}
    </Section>
  )
}
