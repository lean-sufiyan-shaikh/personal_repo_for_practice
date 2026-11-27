import { useTranslation } from 'react-i18next'
import type { OpenableShiftWindow } from '../../../api/shiftDefinitionApi'
import { windowKeyOf } from './shiftWindowKey'

interface Props {
  windows: OpenableShiftWindow[]
  selectedKey: string | null
  onSelect: (key: string) => void
}

/** Shown only within the early start grace, when the ending window and the next one can both be opened. */
export function ShiftWindowChoice({ windows, selectedKey, onSelect }: Props) {
  const { t } = useTranslation('shifts')
  if (windows.length < 2) return null

  return (
    <fieldset className="ui-card space-y-2 p-4">
      <legend className="text-sm font-semibold text-ink">{t('open.window.heading')}</legend>
      <p className="ui-help">{t('open.window.hint')}</p>
      <div className="grid gap-2 sm:grid-cols-2">
        {windows.map((window) => {
          const key = windowKeyOf(window)
          return (
            <label key={key}
              className={`flex cursor-pointer items-start gap-2 rounded-control border p-3 text-sm ${
                key === selectedKey ? 'border-primary bg-info-bg' : 'border-border'}`}>
              <input type="radio" name="shift-window" value={key} checked={key === selectedKey}
                onChange={() => onSelect(key)} className="mt-0.5" />
              <span>
                <span className="block font-medium text-ink">{window.name}</span>
                <span className="block text-xs text-ink-3 tabular-nums">
                  {t(window.startsLater ? 'open.window.startsAt' : 'open.window.endsAt', {
                    start: window.startTime.substring(0, 5), end: window.endTime.substring(0, 5),
                  })}
                </span>
              </span>
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
