import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { parseApiError } from '../../../utils/apiError'
import { userApi } from '../../../api/userApi'
import type { CreateUserRequest, UserGender } from '../../../api/userApi'
import { SearchableSelect } from '../../../components/SearchableSelect'
import { PasswordInput } from '../../../components/PasswordInput'
import { EMPTY_FORM } from './staffForm'
import type { StaffCreateForm } from './staffForm'
import { FormField } from '../../../components/FormField'
import { PASSWORD_POLICY_MESSAGE, PASSWORD_POLICY_REGEX } from '../../../constants/passwordPolicy'
import { LanguagePicker } from '../../../components/LanguagePicker'
import { isAppLanguage } from '../../../i18n/languages'

export function AddStaffForm({ pumpId, canAssignAdmin }: { pumpId: number; canAssignAdmin: boolean }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState(EMPTY_FORM)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  const mutation = useMutation({
    mutationFn: (req: CreateUserRequest) => userApi.createUser(req),
    onSuccess: () => {
      setForm(EMPTY_FORM); setError(null); setSuccess(true)
      setTimeout(() => setSuccess(false), 3000)
      queryClient.invalidateQueries({ queryKey: ['staff', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['operators', pumpId] })
    },
    onError: (err) => setError(parseApiError(err, 'Failed to create user')),
  })

  const set = <K extends keyof StaffCreateForm>(field: K, value: StaffCreateForm[K]) =>
    setForm((prev) => ({ ...prev, [field]: value }))

  const submit = (e: React.FormEvent) => {
    e.preventDefault(); setError(null)
    if (!form.gender) {
      setError('Gender is required')
      return
    }
    if (!PASSWORD_POLICY_REGEX.test(form.password)) {
      setError(PASSWORD_POLICY_MESSAGE)
      return
    }
    mutation.mutate({
      ...form,
      gender: form.gender,
      nightShiftConsent: form.role === 'OPERATOR' && form.gender === 'FEMALE' ? form.nightShiftConsent : false,
      assignedPumpId: pumpId,
    })
  }

  return (
    <div className="border-t border-border pt-5">
      <div className="ui-card-plain ui-card-muted p-4 md:p-5">
        <div className="flex flex-col gap-1 border-b border-border/80 pb-3">
          <p className="ui-label mb-0">Add a new staff member</p>
          <p className="text-sm text-ink-3">
            Capture staff details once here so planning and permissions can use the correct profile.
          </p>
        </div>

        <form onSubmit={submit} className="space-y-4 pt-4">
          <div className="grid gap-4 lg:grid-cols-[minmax(0,1.35fr)_minmax(280px,0.85fr)]">
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label className="block">
                    <span className="ui-label">Full Name</span>
                    <input required value={form.fullName} onChange={(e) => set('fullName', e.target.value)}
                      placeholder="e.g. Raju Kumar"
                      className="text-sm" />
                  </label>
                </div>
                <div>
                  <label className="block">
                    <span className="ui-label">Phone Number</span>
                    <input required value={form.phoneNumber} onChange={(e) => set('phoneNumber', e.target.value)}
                      placeholder="10-digit number" maxLength={10}
                      className="text-sm" />
                  </label>
                </div>
              </div>

              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <FormField label="Password">
                    <PasswordInput required value={form.password} onChange={(e) => set('password', e.target.value)}
                      placeholder="Temporary password"
                      className="text-sm" />
                  </FormField>
                  <p className="ui-help">{PASSWORD_POLICY_MESSAGE}</p>
                  <p className="ui-help">Use a temporary password. The staff member must choose their own at first sign-in.</p>
                </div>
                <div>
                  <span className="ui-label">Gender</span>
                  <SearchableSelect label="Gender"
                    value={form.gender}
                    onChange={v => setForm(prev => ({
                      ...prev,
                      gender: v as UserGender,
                      nightShiftConsent: prev.role === 'OPERATOR' && v === 'FEMALE' ? prev.nightShiftConsent : false,
                    }))}
                    placeholder="Select gender"
                    options={[
                      { value: 'MALE', label: 'Male' },
                      { value: 'FEMALE', label: 'Female' },
                      { value: 'OTHER', label: 'Other' },
                    ]}
                  />
                </div>
              </div>
            </div>

            <div className="ui-card-plain bg-surface border-border p-4 space-y-3">
              <div>
                <p className="ui-label mb-0">Role & shift rules</p>
                <p className="ui-help">Choose the staff role first. Extra planning rules appear only when needed.</p>
              </div>

              <div>
                <span className="ui-label">Role</span>
                <SearchableSelect label="Role"
                  value={form.role}
                  onChange={v => setForm(prev => ({
                    ...prev,
                    role: v as 'OPERATOR' | 'MANAGER' | 'ADMIN' | 'ACCOUNTANT',
                    nightShiftConsent: v === 'OPERATOR' && prev.gender === 'FEMALE' ? prev.nightShiftConsent : false,
                  }))}
                  options={[
                    { value: 'OPERATOR',   label: 'Operator' },
                    { value: 'MANAGER',    label: 'Manager' },
                    ...(canAssignAdmin ? [{ value: 'ADMIN', label: 'Admin' }] : []),
                    { value: 'ACCOUNTANT', label: 'Accountant' },
                  ]}
                />
              </div>

              <LanguagePicker
                value={isAppLanguage(form.preferredLanguage) ? form.preferredLanguage : 'en'}
                onChange={(language) => set('preferredLanguage', language)}
                selectClassName="text-sm"
              />

              {form.role === 'OPERATOR' && form.gender === 'FEMALE' ? (
                <label className="flex items-start gap-3 rounded-card border border-warn bg-warn-bg px-3.5 py-3">
                  <input
                    type="checkbox"
                    checked={!!form.nightShiftConsent}
                    onChange={(e) => set('nightShiftConsent', e.target.checked)}
                    className="mt-0.5 h-4 w-4 accent-warn"
                  />
                  <span className="min-w-0">
                    <span className="block text-sm font-semibold text-warn-strong">Okay for night shift</span>
                    <span className="mt-1 block text-xs leading-5 text-warn-strong">
                      If left unchecked, shift planning will skip night-shift assignment for this operator.
                    </span>
                  </span>
                </label>
              ) : (
                <div className="rounded-card border border-border bg-bg/90 px-3.5 py-3">
                  <p className="text-sm font-medium text-ink">Night-shift consent</p>
                  <p className="mt-1 text-xs leading-5 text-ink-3">
                    This appears only for female operators because it affects night-shift assignment in planning.
                  </p>
                </div>
              )}
            </div>
          </div>

          <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-h-5">
              {error && <p role="alert" className="ui-error-text mt-0">{error}</p>}
              {success && <p className="text-xs text-ok">Staff member added successfully.</p>}
            </div>

            <button type="submit" disabled={mutation.isPending}
              className="ui-btn ui-btn-primary self-start sm:self-auto">
              {mutation.isPending ? 'Adding...' : 'Add Staff Member'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
