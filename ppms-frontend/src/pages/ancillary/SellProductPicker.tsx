import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { AncillaryProduct } from '../../api/ancillaryApi'
import { Modal } from '../../components/Modal'
import { SearchableSelect } from '../../components/SearchableSelect'

/** First step of a sale started without a product, e.g. from the command palette: choose what is being sold. */
export function SellProductPicker({
  products, onPick, onClose,
}: {
  products: AncillaryProduct[]
  onPick: (product: AncillaryProduct) => void
  onClose: () => void
}) {
  const { t } = useTranslation(['ancillary', 'common'])
  const sellable = products.filter(p => p.status === 'ACTIVE' && p.currentStockUnits > 0)
  const [productId, setProductId] = useState('')
  const chosen = sellable.find(p => p.id.toString() === productId)

  return (
    <Modal
      title={t('sell.title')}
      subtitle={t('sellPicker.subtitle')}
      onClose={onClose}
      bodyClassName="space-y-3"
      footer={<>
        <button type="button" onClick={onClose} className="ui-btn ui-btn-secondary">
          {t('common:actions.cancel')}
        </button>
        <button type="button" onClick={() => chosen && onPick(chosen)} disabled={!chosen} className="ui-btn ui-btn-primary disabled:opacity-50">
          {t('sellPicker.continue')}
        </button>
      </>}
    >
      {sellable.length === 0 ? (
        <p className="ui-empty">{t('sellPicker.noneInStock')}</p>
      ) : (
        <div>
          <span className="ui-label">{t('fields.productRequired')}</span>
          <SearchableSelect
            label={t('fields.product')}
            value={productId}
            onChange={setProductId}
            placeholder={t('fields.selectProduct')}
            options={sellable.map(p => ({ value: p.id.toString(), label: t('fields.productOption', { name: p.displayName, stock: p.currentStockUnits }) }))}
          />
        </div>
      )}
    </Modal>
  )
}
