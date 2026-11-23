import { useTranslation } from 'react-i18next'
import { codeLabel } from '../../constants/labels'
import type { TankerDelivery } from '../../api/inventoryApi'
import { Modal } from '../../components/Modal'
import { LitresText } from '../../components/AmountText'
import { FUEL_COLORS, fmtDate } from './inventoryFormat'

// ── Delete invoice confirmation modal ─────────────────────────────────────────

export function DeleteInvoiceModal({ invoice, rows, isPending, error, onConfirm, onClose }: {
  invoice: string
  rows: TankerDelivery[]
  isPending: boolean
  error: string | null
  onConfirm: () => void
  onClose: () => void
}) {
  const { t } = useTranslation(['inventory', 'common'])
  const totalQty = rows.reduce((s, r) => s + r.quantityDelivered, 0)
  return (
    <Modal
      title={t('deleteInvoice.title')}
      tone="danger"
      onClose={onClose}
      bodyClassName="space-y-4"
      footerClassName="justify-end gap-2"
      footer={<>
        <button onClick={onClose} disabled={isPending} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button onClick={onConfirm} disabled={isPending} className="ui-btn ui-btn-danger">
          {isPending ? t('deleteInvoice.deleting') : t('deleteInvoice.confirm')}
        </button>
      </>}
    >
      <p className="text-sm text-ink-2">
        {t('deleteInvoice.warning')}
      </p>

      <div className="bg-surface-sunken border border-border rounded-control p-3 space-y-2">
        <div className="flex items-center gap-2">
          <span className="text-xs text-ink-3">{t('deliveries.billNo')}</span>
          <span className="font-mono text-xs font-semibold text-info bg-info-bg border border-border px-2 py-0.5 rounded-badge">
            {invoice}
          </span>
          <span className="text-xs text-ink-3 ml-auto">{fmtDate(rows[0].deliveryDate)}</span>
        </div>
        <div className="divide-y divide-border">
          {rows.map(r => (
            <div key={r.id} className="flex items-center justify-between py-1.5 text-xs">
              <span className="font-medium text-ink">{r.tankIdentifier}</span>
              <span className={`px-1.5 py-0.5 rounded-badge text-xs font-medium ${(FUEL_COLORS[r.fuelType] ?? FUEL_COLORS.PETROL).badge}`}>
                {codeLabel(r.fuelType)}
              </span>
              <LitresText value={-r.quantityDelivered} showSign className="text-ink-2" />
            </div>
          ))}
        </div>
        <div className="flex justify-between pt-1 border-t border-border text-xs font-semibold text-ink">
          <span>{t('deleteInvoice.totalRemoved')}</span>
          <LitresText value={totalQty} />
        </div>
      </div>

      {error && <p role="alert" className="ui-error-text">{error}</p>}
    </Modal>
  )
}
