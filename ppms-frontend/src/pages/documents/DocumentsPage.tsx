import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, Check, Clock } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { documentApi } from '../../api/documentApi'
import type { PumpDocument, UpsertDocumentRequest, DocumentStatus } from '../../api/documentApi'
import { SkeletonRows } from '../../components/Skeleton'
import { Spinner } from '../../components/Spinner'
import { ConfirmButton } from '../../components/ConfirmButton'
import { Reveal } from '../../components/Reveal'
import { formatIstDate } from '../../utils/date'
import { parseApiError } from '../../utils/apiError'
import { useToastStore } from '../../store/toastStore'
import { codeLabel } from '../../constants/labels'
import { StatusBadge } from '../../components/StatusBadge'
import type { StatusBadgeSpec } from '../../components/statusBadges'

const STATUS_BADGES: Record<DocumentStatus, Omit<StatusBadgeSpec, 'label'>> = {
  VALID:          { tone: 'ok', icon: Check },
  EXPIRING_SOON:  { tone: 'warn', icon: Clock },
  EXPIRED:        { tone: 'bad', icon: AlertTriangle },
}

const EMPTY_FORM: UpsertDocumentRequest = { name: '', docType: '', expiryDate: null, notes: null }

export default function DocumentsPage() {
  const { t } = useTranslation(['documents', 'common'])
  const { user } = useAuthStore()
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()
  const { addToast } = useToastStore()

  const { data: docs = [], isLoading } = useQuery({
    queryKey:  ['documents', pumpId],
    queryFn:   () => documentApi.getDocuments(pumpId!),
    enabled:   !!pumpId,
  })

  const [form, setForm] = useState<UpsertDocumentRequest>(EMPTY_FORM)
  const [editId, setEditId] = useState<number | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [showForm, setShowForm] = useState(false)

  const saveMutation = useMutation({
    mutationFn: (data: UpsertDocumentRequest) =>
      editId
        ? documentApi.updateDocument(pumpId!, editId, data)
        : documentApi.createDocument(pumpId!, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['documents', pumpId] })
      addToast(editId ? t('toast.updated') : t('toast.added'), 'success')
      setForm(EMPTY_FORM)
      setEditId(null)
      setShowForm(false)
      setFormError(null)
    },
    onError: (err) => setFormError(parseApiError(err, t('toast.saveFailed'))),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => documentApi.deleteDocument(pumpId!, id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['documents', pumpId] })
      addToast(t('toast.deleted'), 'success')
    },
    onError: (err) => {
      addToast(parseApiError(err, t('toast.deleteFailed')), 'error')
    },
  })

  const startEdit = (doc: PumpDocument) => {
    setForm({ name: doc.name, docType: doc.docType, expiryDate: doc.expiryDate, notes: doc.notes })
    setEditId(doc.id)
    setShowForm(true)
    setFormError(null)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim() || !form.docType.trim()) { setFormError(t('form.required')); return }
    saveMutation.mutate(form)
  }

  const expiredCount     = docs.filter(d => d.status === 'EXPIRED').length
  const expiringSoonCount = docs.filter(d => d.status === 'EXPIRING_SOON').length
  const isOwner = user?.role === 'OWNER'

  return (
    <div className="ui-page ui-page--narrow space-y-5">

      {/* ── Header ── */}
      <Reveal delay={60}>
      <div className="ui-page-header">
        <div>
          <h1 className="ui-title-sm">{t('page.title')}</h1>
          <p className="ui-subtitle">{t('page.subtitle')}</p>
        </div>
        <button
          onClick={() => { setForm(EMPTY_FORM); setEditId(null); setShowForm(s => !s); setFormError(null) }}
          className="ui-btn ui-btn-primary"
        >
          {showForm ? t('common:actions.cancel') : t('page.addButton')}
        </button>
      </div>
      </Reveal>

      {/* ── Alert strip ── */}
      {(expiredCount > 0 || expiringSoonCount > 0) && (
        <Reveal delay={120}>
        <div className="ui-alert ui-alert-danger">
          <p className="text-sm font-semibold text-bad">{t('alert.title')}</p>
          <p className="text-xs text-bad mt-0.5">
            {expiredCount > 0 && <>{t('alert.expired', { count: expiredCount })}{' '}</>}
            {expiringSoonCount > 0 && t('alert.expiringSoon', { count: expiringSoonCount })}
          </p>
        </div>
        </Reveal>
      )}

      {/* ── Add/edit form ── */}
      {showForm && (
        <form onSubmit={handleSubmit} className="ui-card ui-form-shell">
          <div className="ui-form-shell__head">
            <div>
              <p className="ui-section-kicker mb-2">{t('form.kicker')}</p>
              <h2 className="ui-title-sm">{editId ? t('form.editTitle') : t('form.addTitle')}</h2>
              <p className="ui-subtitle mt-1">{t('form.subtitle')}</p>
            </div>
          </div>

          <div className="ui-form-shell__grid">
            <div>
              <label className="block">
                <span className="ui-label">{t('form.name')}</span>
                <input
                  type="text"
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  className="text-sm"
                  placeholder={t('form.namePlaceholder')}
                  required
                />
              </label>
            </div>

            <div>
              <label className="block">
                <span className="ui-label">{t('form.type')}</span>
                <input
                  type="text"
                  value={form.docType}
                  onChange={e => setForm(f => ({ ...f, docType: e.target.value }))}
                  className="text-sm"
                  placeholder={t('form.typePlaceholder')}
                  required
                />
              </label>
            </div>

            <div>
              <label className="block">
                <span className="ui-label">{t('form.expiryDate')}</span>
                <input
                  type="date"
                  value={form.expiryDate ?? ''}
                  onChange={e => setForm(f => ({ ...f, expiryDate: e.target.value || null }))}
                  className="text-sm"
                />
              </label>
            </div>

            <div>
              <label className="block">
                <span className="ui-label">{t('form.notes')}</span>
                <input
                  type="text"
                  value={form.notes ?? ''}
                  onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
                  className="text-sm"
                  placeholder={t('form.notesPlaceholder')}
                />
              </label>
            </div>
          </div>

          {formError && <p role="alert" className="ui-error-text">{formError}</p>}

          <button
            type="submit"
            disabled={saveMutation.isPending}
            className="ui-btn ui-btn-primary"
          >
            {saveMutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner />{t('common:actions.saving')}</span>
              : editId ? t('form.update') : t('form.add')}
          </button>
        </form>
      )}

      {/* ── Document list ── */}
      <Reveal delay={180}>
      <div className="ui-card p-0 overflow-hidden">
        <div className="ui-toolbar">
          <p className="ui-toolbar-title">{t('list.title', { count: docs.length })}</p>
        </div>

        {isLoading ? (
          <div className="px-5 py-4"><SkeletonRows count={4} /></div>
        ) : docs.length === 0 ? (
          <p className="ui-empty px-5 py-6">{t('list.empty')}</p>
        ) : (
          <div className="ui-record-list">
            {docs.map(doc => (
              <div key={doc.id} className="ui-record-row">
                <div className="ui-record-row__main">
                  <div className="ui-record-row__title">
                    <p className="text-sm font-medium text-ink">{doc.name}</p>
                    <StatusBadge {...STATUS_BADGES[doc.status]} label={codeLabel(doc.status)} />
                  </div>
                  <div className="ui-record-row__meta">
                    <p className="text-xs text-ink-3">{doc.docType}</p>
                    <span className="text-xs text-ink-3">·</span>
                    <p className="text-xs text-ink-3">{t('list.expires', { date: doc.expiryDate ? formatIstDate(doc.expiryDate) : t('list.noExpiry') })}</p>
                    {doc.notes && <><span className="text-xs text-ink-3">·</span><p className="text-xs text-ink-3 truncate">{doc.notes}</p></>}
                  </div>
                </div>
                <div className="ui-record-row__actions">
                  <button
                    onClick={() => startEdit(doc)}
                    className="ui-btn ui-btn-ghost min-h-0 px-0 py-0 text-xs text-primary hover:text-primary-strong"
                  >
                    {t('common:actions.edit')}
                  </button>
                  {isOwner && (
                    <ConfirmButton
                      onConfirm={() => deleteMutation.mutate(doc.id)}
                      disabled={deleteMutation.isPending}
                      triggerAriaLabel={t('list.deleteNamed', { name: doc.name })}
                    />
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
      </Reveal>
    </div>
  )
}
