import { useTranslation } from 'react-i18next'
import { Spinner } from '../../../components/Spinner'

interface Props {
  nozzleNumber: number
  error: string | null
  isPending: boolean
  onDisable: () => void
  onCancel: () => void
}

export function DisableNozzlePanel({ nozzleNumber, error, isPending, onDisable, onCancel }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  return (
    <div className="px-4 py-3 border-t border-border">
      <div className="ui-disable-editor">
        <div className="ui-disable-editor__hero">
          <div>
            <p className="ui-section-kicker mb-2">{t('nozzles.disable.kicker')}</p>
            <h3 className="ui-title-sm">{t('nozzles.disable.title', { number: nozzleNumber })}</h3>
            <p className="ui-subtitle mt-1">{t('nozzles.disable.subtitle')}</p>
          </div>
        </div>
        <div className="ui-alert ui-alert-danger text-xs">
          {t('nozzles.disable.warning')}
        </div>
        {error && <p role="alert" className="ui-error-text">{error}</p>}
        <div className="ui-disable-editor__actions">
          <button
            type="button"
            onClick={onDisable}
            disabled={isPending}
            className="ui-btn ui-btn-danger"
          >
            {isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-3 h-3" />{t('nozzles.disable.disabling')}</span> : t('nozzles.disable.confirm')}
          </button>
          <button type="button" onClick={onCancel} className="ui-btn ui-btn-secondary">
            {t('common:actions.cancel')}
          </button>
        </div>
      </div>
    </div>
  )
}
