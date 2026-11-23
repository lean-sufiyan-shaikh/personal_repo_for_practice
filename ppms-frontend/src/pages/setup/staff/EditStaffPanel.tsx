import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Pencil } from 'lucide-react'
import { parseApiError } from '../../../utils/apiError'
import { userApi } from '../../../api/userApi'
import type { StaffMember, UpdateStaffDetailsRequest, UserGender } from '../../../api/userApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { codeLabel } from '../../../constants/labels'
import { Spinner } from '../../../components/Spinner'
import { LanguagePicker } from '../../../components/LanguagePicker'
import { isAppLanguage } from '../../../i18n/languages'
import type { AppLanguage } from '../../../i18n/languages'

interface Props {
  pumpId: number
  member: StaffMember
  canAssignAdmin: boolean
  onClose: () => void
}

export function EditStaffPanel({ pumpId, member, canAssignAdmin, onClose }: Props) {
  const { t } = useTranslation(['setup', 'common'])
  const queryClient = useQueryClient()
  const [editFullName, setEditFullName]               = useState(member.fullName)
  const [editPhone, setEditPhone]                     = useState(member.phoneNumber ?? '')
  const [editRole, setEditRole]                       = useState<string>(member.role)
  const [editGender, setEditGender]                   = useState<UserGender | ''>((member.gender as UserGender | null) ?? '')
  const [editNightShiftConsent, setEditNightShiftConsent] = useState(member.nightShiftConsent)
  const [editLanguage, setEditLanguage]               = useState<AppLanguage>(isAppLanguage(member.preferredLanguage) ? member.preferredLanguage : 'en')
  const [editError, setEditError]                     = useState<string | null>(null)

  const detailsMutation = useMutation({
    mutationFn: ({ userId, req }: { userId: number; req: UpdateStaffDetailsRequest }) =>
      userApi.updateStaffDetails(userId, req),
    onSuccess: () => {
      setEditError(null)
      onClose()
      queryClient.invalidateQueries({ queryKey: ['staff', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['operators', pumpId] })
    },
    onError: (err) => setEditError(parseApiError(err, t('staff.edit.failed'))),
  })

  return (
    <div className="border-t border-info bg-info-bg/30 px-4 py-4">
      {/* Panel header */}
      <div className="mb-4 flex items-center gap-2.5">
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-info-bg text-primary">
          <Pencil size={13} strokeWidth={2.5} />
        </div>
        <div>
          <p className="text-sm font-semibold text-ink">{t('staff.edit.title')}</p>
          <p className="text-xs text-ink-3">{t('staff.edit.subtitle')}</p>
        </div>
      </div>

      <div className="max-w-md space-y-3">
        {/* Row 1: Name + Phone */}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block">
              <span className="ui-label">{t('staff.fullName')}</span>
              <input
                type="text"
                value={editFullName}
                onChange={e => setEditFullName(e.target.value)}
                placeholder={t('staff.fullNamePlaceholder')}
                className="text-sm"
              />
            </label>
          </div>
          <div>
            <label className="block">
              <span className="ui-label">{t('staff.phone')}</span>
              <input
                type="text"
                value={editPhone}
                onChange={e => setEditPhone(e.target.value)}
                placeholder={t('staff.phonePlaceholder')}
                maxLength={10}
                className="text-sm"
              />
            </label>
          </div>
        </div>

        {/* Row 2: Role + Gender */}
        <div className="grid grid-cols-2 gap-3">
          <div>
            <span className="ui-label">{t('staff.role')}</span>
            <SearchableSelect label={t('staff.role')}
              value={editRole}
              onChange={v => {
                setEditRole(v)
                if (v !== 'OPERATOR') setEditNightShiftConsent(false)
              }}
              placeholder={t('staff.selectRole')}
              size="sm"
              options={[
                { value: 'OPERATOR',   label: codeLabel('OPERATOR') },
                { value: 'MANAGER',    label: codeLabel('MANAGER') },
                ...(canAssignAdmin ? [{ value: 'ADMIN', label: codeLabel('ADMIN') }] : []),
                { value: 'ACCOUNTANT', label: codeLabel('ACCOUNTANT') },
              ]}
            />
          </div>
          <div>
            <span className="ui-label">{t('staff.gender')}</span>
            <SearchableSelect label={t('staff.gender')}
              value={editGender}
              onChange={v => {
                setEditGender(v as UserGender | '')
                if (v !== 'FEMALE') setEditNightShiftConsent(false)
              }}
              placeholder={t('staff.selectGender')}
              size="sm"
              options={[
                { value: 'MALE',   label: codeLabel('MALE') },
                { value: 'FEMALE', label: codeLabel('FEMALE') },
                { value: 'OTHER',  label: codeLabel('OTHER') },
              ]}
            />
          </div>
        </div>

        <LanguagePicker value={editLanguage} onChange={setEditLanguage} className="max-w-48" selectClassName="text-sm" />

        {/* Night shift consent — only for female operators */}
        {editRole === 'OPERATOR' && editGender === 'FEMALE' && (
          <label className="flex cursor-pointer items-start gap-3 rounded-card border border-warn bg-warn-bg px-3.5 py-3">
            <input
              type="checkbox"
              checked={editNightShiftConsent}
              onChange={e => setEditNightShiftConsent(e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-warn"
            />
            <span className="min-w-0">
              <span className="block text-sm font-semibold text-warn-strong">{t('staff.nightShiftConsent')}</span>
              <span className="mt-1 block text-xs leading-5 text-warn-strong">
                {t('staff.nightShiftConsentHint')}
              </span>
            </span>
          </label>
        )}

        {editError && <p role="alert" className="ui-error-text">{editError}</p>}

        <div className="flex gap-2 pt-1">
          <button
            type="button"
            disabled={detailsMutation.isPending || !editFullName.trim() || !editPhone.trim() || !editRole}
            onClick={() => detailsMutation.mutate({
              userId: member.id,
              req: {
                fullName: editFullName.trim(),
                phoneNumber: editPhone.trim(),
                role: editRole as UpdateStaffDetailsRequest['role'],
                gender: editGender || undefined,
                nightShiftConsent: editRole === 'OPERATOR' && editGender === 'FEMALE'
                  ? editNightShiftConsent
                  : false,
                preferredLanguage: editLanguage,
              },
            })}
            className="ui-btn ui-btn-primary min-h-0 px-4 py-2 text-sm"
          >
            {detailsMutation.isPending ? <span className="flex items-center gap-1.5"><Spinner className="w-3 h-3" />{t('common:actions.saving')}</span> : t('staff.edit.save')}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="ui-btn ui-btn-secondary min-h-0 px-4 py-2 text-sm"
          >
            {t('common:actions.cancel')}
          </button>
        </div>
      </div>
    </div>
  )
}
