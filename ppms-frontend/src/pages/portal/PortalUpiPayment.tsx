import { useTranslation } from 'react-i18next'
import { UpiQrCode } from '../../components/UpiQrCode'
import { useIsPhone } from '../../hooks/useIsPhone'
import { formatCurrency } from '../../utils/format'

/** Pay the outstanding by UPI: a button that opens the UPI app on a phone, and a QR code to scan from another screen. */
export function PortalUpiPayment({ link, amount }: { link: string; amount: number }) {
  const { t } = useTranslation('portal')
  const isPhone = useIsPhone()
  return (
    <div className="mt-3 flex flex-col items-center gap-2 border-t border-border pt-3 text-center">
      {isPhone && (
        <a href={link} className="ui-btn ui-btn-primary w-full">{t('result.payUpi')}</a>
      )}
      <UpiQrCode link={link} size={168} label={t('result.qrLabel', { amount: formatCurrency(amount) })} />
      <p className="text-xs text-ink-3">{t('result.scanToPay', { amount: formatCurrency(amount) })}</p>
      <p className="text-xs text-ink-3">{t('result.confirmNote')}</p>
    </div>
  )
}
