import { Beaker } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { FormField } from '../../components/FormField'
import { FormGrid } from '../../components/layout/FormGrid'
import { NumericInput } from '../../components/NumericInput'
import { formatDecimal } from '../../utils/format'
import { SetupFormGroup } from './SetupFormGroup'
import { MAX_TEST_LITRES_LIMIT, MAX_TEST_VOLUME_LITRES, litresInRange } from './testLitresSettings'

/** The daily nozzle test measure offered at close, and the test litres per nozzle that need a note. */
export function TestLitresSettingsGroup({ testVolume, maxTestLitres, onTestVolumeChange, onMaxTestLitresChange }: {
  testVolume: string
  maxTestLitres: string
  onTestVolumeChange: (value: string) => void
  onMaxTestLitresChange: (value: string) => void
}) {
  const { t } = useTranslation('setup')
  const volumeValid = litresInRange(testVolume, MAX_TEST_VOLUME_LITRES)
  const maxValid = litresInRange(maxTestLitres, MAX_TEST_LITRES_LIMIT)
  return (
    <SetupFormGroup icon={Beaker} title={t('settings.testLitresTitle')} hint={t('settings.testLitresExplanation')}>
      <FormGrid>
        <FormField label={t('settings.testVolumeLabel')}
          help={volumeValid ? t('settings.testVolumeEffect', { litres: formatDecimal(Number(testVolume)) }) : undefined}
          error={volumeValid ? undefined : t('settings.testLitresInvalid', { max: MAX_TEST_VOLUME_LITRES })}>
          <NumericInput min="0" max={MAX_TEST_VOLUME_LITRES} step="0.01" unit="L" value={testVolume}
            onChange={(e) => onTestVolumeChange(e.target.value)} />
        </FormField>
        <FormField label={t('settings.maxTestLitresLabel')}
          help={maxValid ? t('settings.maxTestLitresEffect', { litres: formatDecimal(Number(maxTestLitres)) }) : undefined}
          error={maxValid ? undefined : t('settings.testLitresInvalid', { max: MAX_TEST_LITRES_LIMIT })}>
          <NumericInput min="0" max={MAX_TEST_LITRES_LIMIT} step="0.01" unit="L" value={maxTestLitres}
            onChange={(e) => onMaxTestLitresChange(e.target.value)} />
        </FormField>
      </FormGrid>
    </SetupFormGroup>
  )
}
