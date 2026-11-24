import { Fuel } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { ForecourtIllustration } from './ForecourtIllustration'

/** The brand half of every sign-in screen; on phones it shrinks to a header above the form. */
export function AuthBrandPanel() {
  const { t } = useTranslation('auth')
  return (
    <div className="ui-auth-brand">
      <p className="ui-auth-brand__logo">
        <span className="ui-auth-brand__mark">
          <Fuel size={20} strokeWidth={2.2} aria-hidden="true" />
        </span>
        PPMS
      </p>
      <ForecourtIllustration className="ui-auth-brand__art" />
      <div className="ui-auth-brand__text">
        <p className="ui-auth-brand__tagline">{t('brand.tagline')}</p>
        <p className="ui-auth-brand__line">{t('brand.supportingLine')}</p>
      </div>
    </div>
  )
}
