
import { useTranslation } from 'react-i18next'

export function TankerForm({
  name, setName, capacity, setCapacity, tankerType, setTankerType, makeDefault, setMakeDefault, error,
}: {
  name: string; setName: (v: string) => void
  capacity: string; setCapacity: (v: string) => void
  tankerType: 'OWN' | 'COMPANY'; setTankerType: (v: 'OWN' | 'COMPANY') => void
  makeDefault: boolean; setMakeDefault: (v: boolean) => void
  error: string | null
}) {
  const { t } = useTranslation('setup')
  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <label className="block">
            <span className="ui-label">{t('tankers.name')}</span>
            <input
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder={t('tankers.namePlaceholder')}
              className="text-sm"
            />
          </label>
        </div>
        <div className="space-y-1">
          <label className="block">
            <span className="ui-label">{t('tankers.capacity')}</span>
            <input
              type="number"
              min="100"
              step="100"
              value={capacity}
              onChange={e => setCapacity(e.target.value)}
              placeholder={t('example', { value: '12000' })}
              className="text-sm"
            />
          </label>
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <label className="block">
            <span className="ui-label">{t('tankers.type')}</span>
            <select
              value={tankerType}
              onChange={e => setTankerType(e.target.value as 'OWN' | 'COMPANY')}
              className="ui-input text-sm"
            >
              <option value="COMPANY">{t('tankers.companyTanker')}</option>
              <option value="OWN">{t('tankers.ownTanker')}</option>
            </select>
          </label>
        </div>
        <div className="flex items-end pb-2">
          <label className="flex items-center gap-2 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={makeDefault}
              onChange={e => setMakeDefault(e.target.checked)}
              className="rounded-badge accent-primary"
            />
            <span className="text-sm text-ink">{t('tankers.setAsDefault')}</span>
          </label>
        </div>
      </div>
      {error && <p role="alert" className="ui-error-text">{error}</p>}
    </div>
  )
}
