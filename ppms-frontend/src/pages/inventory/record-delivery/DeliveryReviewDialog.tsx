import { useTranslation } from 'react-i18next'
import { codeLabel } from '../../../constants/labels'
import { Modal } from '../../../components/Modal'
import { formatNumberIn } from '../../../i18n/format'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { FUEL_COLORS, fmtDate } from '../inventoryFormat'
import type { PreparedDeliveryItem } from './deliveryRows'

interface Props {
  items: PreparedDeliveryItem[]
  date: string
  invoice: string
  error: string | null
  isOwnerOrAdmin: boolean
  canSetBillTotal: boolean
  billTotalManual: boolean
  billTotalNum: number
  otherCharges: number
  isPending: boolean
  onBack: () => void
  onConfirm: () => void
}

export function DeliveryReviewDialog({
  items, date, invoice, error, isOwnerOrAdmin, canSetBillTotal, billTotalManual, billTotalNum, otherCharges,
  isPending, onBack, onConfirm,
}: Props) {
  const { t } = useTranslation(['inventory', 'common'])
  return (
    <Modal
      title={t('review.title')}
      subtitle={t('review.subtitle')}
      tone="info"
      size="full"
      onClose={onBack}
      closeOnBackdropClick
      bodyClassName="space-y-5"
      footer={<>
          <button
            type="button"
            onClick={onBack}
            disabled={isPending}
            className="ui-btn ui-btn-secondary flex-1"
          >
            {t('review.backToEdit')}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={isPending}
            className="ui-btn ui-btn-primary flex-1"
          >
            {isPending ? t('review.recording') : t('review.confirm', { count: items.length })}
          </button>
      </>}
    >
          {error && (
            <div role="alert" className="ui-alert ui-alert-danger text-sm">
              {t('common:notices.goBackRetry', { error })}
            </div>
          )}
          <div className={`grid gap-3 ${(canSetBillTotal && billTotalNum > 0) || isOwnerOrAdmin ? 'grid-cols-1 sm:grid-cols-3' : 'grid-cols-1 sm:grid-cols-2'}`}>
            <div className="rounded-card border border-border bg-surface-sunken px-4 py-3">
              <p className="text-xs font-medium text-ink-3">{t('review.date')}</p>
              <p className="mt-1 text-sm font-semibold text-ink">{fmtDate(date)}</p>
            </div>
            <div className="rounded-card border border-border bg-surface-sunken px-4 py-3">
              <p className="text-xs font-medium text-ink-3">{t('review.invoice')}</p>
              <p className="mt-1 text-sm font-semibold text-ink">{invoice.trim()}</p>
            </div>
            {(canSetBillTotal && billTotalNum > 0) && (() => {
              const calcTotal = items.reduce((sum, item) => sum + item.totalCost, 0)
              const displayTotal = billTotalNum > 0 ? billTotalNum : calcTotal
              return (
                <div className="rounded-card border border-primary bg-info-bg px-4 py-3 shadow-raised">
                  <p className="text-xs font-medium text-info">{t('review.billAmount')}</p>
                  <p className="mt-1 text-base font-bold text-info">
                    <MoneyText value={displayTotal} />
                  </p>
                  {isOwnerOrAdmin && billTotalManual && otherCharges !== 0 && (
                    <p className="text-xs mt-1 text-info">
                      {t('review.calculated', { amount: formatNumberIn(calcTotal, { minimumFractionDigits: 2 }) })}
                      {' · '}
                      <span className={otherCharges > 0 ? 'text-warn' : 'text-ok'}>
                        {t('review.otherCharges', { amount: `${otherCharges > 0 ? '+' : ''}₹${formatNumberIn(Math.abs(otherCharges), { minimumFractionDigits: 2 })}` })}
                      </span>
                    </p>
                  )}
                </div>
              )
            })()}
          </div>

          <div className="ui-card p-0 overflow-hidden">
            <div className={`grid gap-3 bg-surface-sunken px-4 py-3 text-xs font-semibold uppercase tracking-wide text-ink-3 ${
              isOwnerOrAdmin
                ? 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1fr)]'
                : 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,1fr)]'
            }`}>
              <span>{t('columns.tank')}</span>
              <span>{t('columns.fuel')}</span>
              <span className="text-right">{t('columns.quantity')}</span>
              {isOwnerOrAdmin && <span className="text-right">{t('columns.rate')}</span>}
              {isOwnerOrAdmin && <span className="text-right">{t('columns.amount')}</span>}
            </div>
            <div className="divide-y divide-border">
              {items.map((item) => (
                <div key={item.tankId} className="px-4 py-3">
                  <div className={`grid gap-3 items-center text-sm ${
                    isOwnerOrAdmin
                      ? 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1fr)]'
                      : 'grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,1fr)]'
                  }`}>
                    <span className="font-semibold text-ink">{item.tankIdentifier}</span>
                    <span className={`inline-flex w-fit rounded-badge px-2 py-0.5 text-xs font-medium ${FUEL_COLORS[item.fuelType]?.badge ?? FUEL_COLORS.PETROL.badge}`}>
                      {codeLabel(item.fuelType)}
                    </span>
                    <LitresText value={item.quantityDelivered} className="text-right text-ink-2" />
                    {isOwnerOrAdmin && (
                      <span className="text-right text-ink-3 tabular-nums">
                        ₹{item.costPricePerUnit.toFixed(4)}/L
                      </span>
                    )}
                    {isOwnerOrAdmin && (
                      <MoneyText value={item.totalCost} className="text-right font-semibold text-ink" />
                    )}
                  </div>
                  {item.stockAfter !== null && (
                    <p className="mt-2 text-xs text-ink-3">
                      {t('review.stockAfterDelivery')} <span className="font-medium text-ink tabular-nums">
                        {formatNumberIn(item.stockAfter, { minimumFractionDigits: 1 })} L
                      </span>
                      {item.capacity !== null && <>{' '}{t('review.ofCapacity', { capacity: formatNumberIn(item.capacity, { minimumFractionDigits: 0 }) })}</>}
                    </p>
                  )}
                </div>
              ))}
            </div>
          </div>
    </Modal>
  )
}
