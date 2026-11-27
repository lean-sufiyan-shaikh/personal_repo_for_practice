import type { OpenableShiftWindow } from '../../../api/shiftDefinitionApi'

export const windowKeyOf = (window: OpenableShiftWindow) => `${window.definitionId}:${window.shiftDate}`
