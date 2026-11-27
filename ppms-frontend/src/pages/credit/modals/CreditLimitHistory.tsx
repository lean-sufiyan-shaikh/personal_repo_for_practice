import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { creditApi } from '../../../api/creditApi'
import { formatIstDateTime } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

interface CreditLimitHistoryProps {
  pumpId: number
  clientId: number
}

/** Every change to the client's limit, newest first, with who made it and why. */
export function CreditLimitHistory({ pumpId, clientId }: CreditLimitHistoryProps) {
  const { t } = useTranslation('credit')
  const { data: changes = [], isLoading, isError } = useQuery({
    queryKey: ['credit-limit-changes', pumpId, clientId],
    queryFn: () => creditApi.getCreditLimitChanges(pumpId, clientId),
  })
  const limitText = (amount: number, unlimited: boolean) => unlimited ? t('creditLimit.noLimit') : formatCurrency(amount)

  return (
    <section aria-label={t('creditLimit.history.title')} className="space-y-2">
      <h3 className="ui-label">{t('creditLimit.history.title')}</h3>
      {isLoading ? (
        <p className="text-xs text-ink-3">{t('creditLimit.history.loading')}</p>
      ) : isError ? (
        <p role="alert" className="text-xs text-bad">{t('creditLimit.history.failed')}</p>
      ) : changes.length === 0 ? (
        <p className="text-xs text-ink-3">{t('creditLimit.history.empty')}</p>
      ) : (
        <ul className="max-h-48 space-y-2 overflow-y-auto">
          {changes.map(change => (
            <li key={change.id} className="ui-card-plain ui-card-muted px-3 py-2 text-xs">
              <p className="font-medium text-ink">
                {t('creditLimit.history.change', {
                  from: limitText(change.oldLimit, change.oldUnlimited),
                  to: limitText(change.newLimit, change.newUnlimited),
                })}
              </p>
              <p className="text-ink-3">
                {t('creditLimit.history.by', { name: change.changedByName, when: formatIstDateTime(change.changedAt) })}
              </p>
              {change.reason && <p className="text-ink-2">{change.reason}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
