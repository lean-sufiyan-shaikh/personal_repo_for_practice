import { useTranslation } from 'react-i18next'
import { MoneyText } from '../../components/AmountText'
import { PortalUpiPayment } from './PortalUpiPayment'
import { PortalVehicleUsage } from './PortalVehicleUsage'
import type { PortalVehicleMonth } from './PortalVehicleUsage'

export interface PortalBalance {
  /** The whole group's balance for a main account; a sub-account's own credit for a sub-account. */
  outstandingAmount: number
  /** The group's limit; null when the account has no limit or is a sub-account. */
  creditLimit: number | null
  unlimited: boolean
  /** The balance and limit are held on the main account; outstandingAmount is this account's own usage. */
  subAccount: boolean
  /** upi://pay link for the outstanding; null for a sub-account, when the pump takes no UPI payments by link, or nothing is owed. */
  upiPayLink: string | null
  /** This month and the month before, newest first. */
  vehicleUsage: PortalVehicleMonth[]
}

/** What the client sees once the balance page recognises their phone and PIN. */
export function PortalBalanceResult({ result }: { result: PortalBalance }) {
  const { t } = useTranslation('portal')
  return (
    <div role="status" className="ui-card-plain ui-card-muted px-4 py-4">
      <p className="text-xs text-ink-3 mb-3">{result.subAccount ? t('result.subAccount') : t('result.account')}</p>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-xs text-ink-3">{result.subAccount ? t('result.ownUsage') : t('result.outstanding')}</span>
          <MoneyText value={result.outstandingAmount} className="text-sm font-bold text-ink" />
        </div>
        {!result.subAccount && (
          <div className="flex items-center justify-between">
            <span className="text-xs text-ink-3">{t('result.creditLimit')}</span>
            {result.unlimited || result.creditLimit === null
              ? <span className="text-sm text-ink-2">{t('result.noLimit')}</span>
              : <MoneyText value={result.creditLimit} className="text-sm text-ink" />}
          </div>
        )}
      </div>

      {result.subAccount ? (
        <p className="ui-alert ui-alert-info text-xs mt-3">{t('result.subAccountNote')}</p>
      ) : result.outstandingAmount <= 0 && (
        <p className="text-xs text-ok font-medium mt-2">{t('result.settled')}</p>
      )}
      {result.upiPayLink && result.outstandingAmount > 0 && (
        <PortalUpiPayment link={result.upiPayLink} amount={result.outstandingAmount} />
      )}
      {result.vehicleUsage.some((month) => month.vehicles.length > 0) && (
        <PortalVehicleUsage months={result.vehicleUsage} />
      )}
    </div>
  )
}
