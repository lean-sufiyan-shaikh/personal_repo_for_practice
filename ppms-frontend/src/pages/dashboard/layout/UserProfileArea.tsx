import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { authApi } from '../../../api/authApi'
import { useAuthStore } from '../../../store/authStore'
import { userApi } from '../../../api/userApi'
import { ChangePasswordModal } from '../../../components/ChangePasswordModal'
import { useEscapeKey } from '../../../hooks/useEscapeKey'
import { parseApiError } from '../../../utils/apiError'
import { roleLabel } from '../../../constants/labels'
import { LanguagePicker } from '../../../components/LanguagePicker'
import { activeLanguage } from '../../../i18n'
import type { AppLanguage } from '../../../i18n/languages'

export function UserProfileArea() {
  const { t } = useTranslation(['nav', 'common'])
  const navigate = useNavigate()
  const { user, clearAuth, updateUser } = useAuthStore()

  // Inline name edit
  const [editingName, setEditingName] = useState(false)
  const [nameInput, setNameInput] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [profileMenuOpen, setProfileMenuOpen] = useState(false)
  const [passwordModalOpen, setPasswordModalOpen] = useState(false)
  const [languageError, setLanguageError] = useState<string | null>(null)
  const nameInputRef = useRef<HTMLInputElement>(null)
  const profileMenuRef = useRef<HTMLDivElement>(null)
  const profileButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (profileMenuRef.current && !profileMenuRef.current.contains(event.target as Node)) {
        setProfileMenuOpen(false)
      }
    }

    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  useEscapeKey(() => {
    setProfileMenuOpen(false)
    profileButtonRef.current?.focus()
  }, profileMenuOpen && !passwordModalOpen)

  const renameMutation = useMutation({
    mutationFn: (fullName: string) => userApi.updateMyProfile(fullName),
    onSuccess: (data) => {
      updateUser({ fullName: data.fullName })
      setEditingName(false)
      setNameError(null)
    },
    onError: (err) => {
      setNameError(parseApiError(err, t('profile.saveNameFailed')))
    },
  })

  // The screen switches at once; the choice goes back if the server could not save it.
  const languageMutation = useMutation({
    mutationFn: (language: AppLanguage) => userApi.updateMyLanguage(language),
    onMutate: (language) => {
      const previousLanguage = user?.preferredLanguage
      setLanguageError(null)
      updateUser({ preferredLanguage: language })
      return { previousLanguage }
    },
    onError: (err, _language, context) => {
      updateUser({ preferredLanguage: context?.previousLanguage })
      setLanguageError(parseApiError(err, t('common:language.saveFailed')))
    },
  })

  const logoutMutation = useMutation({
    mutationFn: async () => {
      await authApi.logout()
    },
    onSettled: () => {
      clearAuth()
      navigate('/login')
    },
  })

  const startEditing = () => {
    setNameInput(user?.fullName ?? '')
    setNameError(null)
    setEditingName(true)
    setTimeout(() => nameInputRef.current?.select(), 0)
  }

  const saveName = () => {
    const trimmed = nameInput.trim()
    if (!trimmed) { setNameError(t('profile.nameBlank')); return }
    renameMutation.mutate(trimmed)
  }

  const cancelEdit = () => {
    setEditingName(false)
    setNameError(null)
  }

  const openPasswordModal = () => {
    setProfileMenuOpen(false)
    setPasswordModalOpen(true)
  }

  const handleLogout = () => {
    setProfileMenuOpen(false)
    logoutMutation.mutate()
  }

  return (
    <>
      <div className="text-right hidden sm:block">
        {editingName ? (
          <div className="flex flex-col items-end gap-1">
            <div className="flex items-center gap-1">
              <input aria-label={t('profile.yourName')}
                ref={nameInputRef}
                value={nameInput}
                onChange={(e) => setNameInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') saveName(); if (e.key === 'Escape') cancelEdit() }}
                disabled={renameMutation.isPending}
                className="text-xs font-semibold bg-white/10 text-surface border border-white/30 rounded-control px-2 py-0.5 w-36"
              />
              <button
                onClick={saveName}
                disabled={renameMutation.isPending}
                aria-label={t('profile.saveName')}
                className="text-on-dark-2 hover:text-surface p-0.5 disabled:opacity-50"
                title={t('common:actions.save')}
              ><Check size={13} strokeWidth={2.5} /></button>
              <button
                onClick={cancelEdit}
                disabled={renameMutation.isPending}
                aria-label={t('profile.cancelEditing')}
                className="text-on-dark-2 hover:text-surface p-0.5 disabled:opacity-50"
                title={t('common:actions.cancel')}
              ><X size={13} strokeWidth={2} /></button>
            </div>
            {nameError && <p className="text-accent text-xs">{nameError}</p>}
          </div>
        ) : (
          <button
            onClick={startEditing}
            className="group text-right"
            aria-label={t('profile.editName', { name: user?.fullName ?? '' })}
            title={t('profile.editNameHint')}
          >
            <p className="text-xs font-semibold text-surface leading-tight group-hover:text-accent transition-colors">
              {user?.fullName}
              <span aria-hidden="true" className="ml-1 text-on-dark-2 group-hover:text-accent text-xs">✎</span>
            </p>
          </button>
        )}
        <p className="text-xs text-on-dark-2 leading-tight capitalize">{roleLabel(user?.role)}</p>
      </div>
      <div className="relative" ref={profileMenuRef}>
        <button
          ref={profileButtonRef}
          onClick={() => setProfileMenuOpen((open) => !open)}
          aria-label={t('profile.openMenu')}
          aria-expanded={profileMenuOpen}
          aria-haspopup="true"
          className="w-9 h-9 bg-white/10 border border-white/25 rounded-full flex items-center justify-center hover:bg-white/20 transition-colors"
          title={t('profile.openMenu')}
        >
          <span className="text-sm font-bold text-surface">
            {user?.fullName?.charAt(0).toUpperCase()}
          </span>
        </button>

        {profileMenuOpen && (
          <div className="absolute right-0 z-30 mt-2 w-48 overflow-hidden rounded-card border border-border bg-surface shadow-overlay">
            <LanguagePicker
              value={activeLanguage()}
              onChange={(language) => languageMutation.mutate(language)}
              disabled={languageMutation.isPending}
              error={languageError}
              className="border-b border-border px-4 py-3"
              selectClassName="text-sm"
            />
            <button
              onClick={openPasswordModal}
              className="ui-btn ui-btn-ghost w-full justify-start rounded-none border-0 px-4 py-3 text-left text-sm font-medium text-ink hover:bg-bg"
            >
              {t('profile.resetPassword')}
            </button>
            <button
              onClick={handleLogout}
              disabled={logoutMutation.isPending}
              className="ui-btn ui-btn-ghost w-full justify-start rounded-none border-0 px-4 py-3 text-left text-sm font-medium text-bad hover:bg-bad-bg disabled:opacity-50"
            >
              {logoutMutation.isPending ? t('profile.loggingOut') : t('profile.logout')}
            </button>
          </div>
        )}
      </div>
      {passwordModalOpen && <ChangePasswordModal onClose={() => setPasswordModalOpen(false)} />}
    </>
  )
}
