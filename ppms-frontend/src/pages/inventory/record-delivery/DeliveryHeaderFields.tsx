import { Truck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { formatNumberIn } from '../../../i18n/format'
import type { Tanker } from '../../../api/tankerApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { FormGrid, FormGridFullRow } from '../../../components/layout/FormGrid'

interface Props {
  date: string
  setDate: (value: string) => void
  invoice: string
  setInvoice: (value: string) => void
  tankers: Tanker[]
  selectedTanker: Tanker | null
  selectedTankerId: number | null
  setSelectedTankerId: (id: number | null) => void
  tankerMismatch: boolean
  tankerRemaining: number | null
  totalEnteredQty: number
}

export function DeliveryHeaderFields({ date, setDate, invoice, setInvoice, tankers, selectedTanker, selectedTankerId, setSelectedTankerId, tankerMismatch, tankerRemaining, totalEnteredQty }: Props) {
  const { t } = useTranslation('inventory')
  return (
    <div className="border-b border-border pb-4">
      <div className="ui-inline-form">
      <FormGrid>
        <div>
          <label className="block">
            <span className="ui-label">
            {t('header.date')} <span aria-hidden="true" className="text-bad">*</span>
          </span>
            <input
              type="date"
              value={date}
              onChange={e => setDate(e.target.value)}
              className="shadow-raised"
            />
          </label>
        </div>
        <div>
          <label className="block">
            <span className="ui-label">
            {t('header.invoice')} <span aria-hidden="true" className="text-bad">*</span>
          </span>
            <input
              type="text"
              value={invoice}
              onChange={e => setInvoice(e.target.value)}
              placeholder={t('header.invoicePlaceholder')}
              className="shadow-raised"
            />
          </label>
        </div>

      {tankers.length > 0 && (
        <FormGridFullRow>
          <span className="ui-label">{t('header.tanker')}</span>
          <SearchableSelect label={t('header.tanker')}
            value={selectedTankerId !== null ? String(selectedTankerId) : ''}
            onChange={v => setSelectedTankerId(v ? Number(v) : null)}
            options={[
              { value: '', label: t('header.noTanker') },
              ...tankers.map(tanker => ({
                value: String(tanker.id),
                label: t(tanker.defaultTanker ? 'header.tankerOptionDefault' : 'header.tankerOption', { name: tanker.name, capacity: formatNumberIn(tanker.capacityLitres) }),
              })),
            ]}
            placeholder={t('header.selectTanker')}
          />
          {selectedTanker && (
            <div className={`mt-2 flex items-center gap-2 rounded-control px-3 py-2 text-xs ${
              tankerMismatch
                ? 'bg-bad-bg border border-bad text-bad'
                : 'bg-ok-bg border border-ok text-ok'
            }`}>
              <Truck size={12} strokeWidth={2} className="shrink-0" />
              <span>
                {t('header.capacity')} <span className="font-semibold">{formatNumberIn(selectedTanker.capacityLitres)} L</span>
                {' · '}
                {t('header.entered')} <span className="font-semibold">{formatNumberIn(totalEnteredQty, { minimumFractionDigits: 1 })} L</span>
                {tankerRemaining !== null && tankerRemaining !== 0 && (
                  <span className="ml-1">
                    · <span className="font-semibold">{tankerRemaining > 0 ? t('header.litresRemaining', { value: formatNumberIn(tankerRemaining, { minimumFractionDigits: 1 }) }) : t('header.litresOver', { value: formatNumberIn(Math.abs(tankerRemaining), { minimumFractionDigits: 1 }) })}</span>
                  </span>
                )}
              </span>
            </div>
          )}
        </FormGridFullRow>
      )}
      </FormGrid>
      </div>
    </div>
  )
}
