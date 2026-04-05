import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useAuthStore } from '../../store/authStore'
import { usePumpStore } from '../../store/usePumpStore'
import { documentApi } from '../../api/documentApi'
import type { PumpDocument, UpsertDocumentRequest, DocumentStatus } from '../../api/documentApi'

const STATUS_STYLES: Record<DocumentStatus, string> = {
  VALID:          'bg-emerald-100 text-emerald-700',
  EXPIRING_SOON:  'bg-amber-100 text-amber-700',
  EXPIRED:        'bg-red-100 text-red-700',
}

function fmtDate(d: string | null) {
  if (!d) return 'No expiry'
  return new Date(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

const EMPTY_FORM: UpsertDocumentRequest = { name: '', docType: '', expiryDate: null, notes: null }

export default function DocumentsPage() {
  const { user } = useAuthStore()
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()

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
      setForm(EMPTY_FORM)
      setEditId(null)
      setShowForm(false)
      setFormError(null)
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to save document'),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => documentApi.deleteDocument(pumpId!, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['documents', pumpId] }),
  })

  const startEdit = (doc: PumpDocument) => {
    setForm({ name: doc.name, docType: doc.docType, expiryDate: doc.expiryDate, notes: doc.notes })
    setEditId(doc.id)
    setShowForm(true)
    setFormError(null)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim() || !form.docType.trim()) { setFormError('Name and document type are required'); return }
    saveMutation.mutate(form)
  }

  const expiredCount     = docs.filter(d => d.status === 'EXPIRED').length
  const expiringSoonCount = docs.filter(d => d.status === 'EXPIRING_SOON').length
  const isOwner = user?.role === 'OWNER'

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Header ── */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-800">Compliance Documents</h1>
          <p className="text-sm text-slate-500 mt-0.5">Track licences, permits, and certificates</p>
        </div>
        <button
          onClick={() => { setForm(EMPTY_FORM); setEditId(null); setShowForm(s => !s); setFormError(null) }}
          className="bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg hover:bg-blue-700 transition-colors"
        >
          {showForm ? 'Cancel' : '+ Add Document'}
        </button>
      </div>

      {/* ── Alert strip ── */}
      {(expiredCount > 0 || expiringSoonCount > 0) && (
        <div className="bg-red-50 border border-red-200 rounded-xl px-5 py-3">
          <p className="text-sm font-semibold text-red-700">Compliance attention needed</p>
          <p className="text-xs text-red-500 mt-0.5">
            {expiredCount > 0 && `${expiredCount} document${expiredCount !== 1 ? 's' : ''} expired. `}
            {expiringSoonCount > 0 && `${expiringSoonCount} document${expiringSoonCount !== 1 ? 's' : ''} expiring within 30 days.`}
          </p>
        </div>
      )}

      {/* ── Add/edit form ── */}
      {showForm && (
        <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 px-5 py-4 space-y-3">
          <p className="text-sm font-semibold text-slate-700">{editId ? 'Edit Document' : 'Add Document'}</p>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="text-xs text-slate-500 block mb-1">Document Name</label>
              <input
                type="text"
                value={form.name}
                onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
                placeholder="e.g. Petrol Pump Licence"
                required
              />
            </div>

            <div>
              <label className="text-xs text-slate-500 block mb-1">Document Type</label>
              <input
                type="text"
                value={form.docType}
                onChange={e => setForm(f => ({ ...f, docType: e.target.value }))}
                className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
                placeholder="e.g. Licence, Certificate, Permit"
                required
              />
            </div>

            <div>
              <label className="text-xs text-slate-500 block mb-1">Expiry Date (optional)</label>
              <input
                type="date"
                value={form.expiryDate ?? ''}
                onChange={e => setForm(f => ({ ...f, expiryDate: e.target.value || null }))}
                className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              />
            </div>

            <div>
              <label className="text-xs text-slate-500 block mb-1">Notes (optional)</label>
              <input
                type="text"
                value={form.notes ?? ''}
                onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
                className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
                placeholder="Any additional notes"
              />
            </div>
          </div>

          {formError && <p className="text-xs text-red-600">{formError}</p>}

          <button
            type="submit"
            disabled={saveMutation.isPending}
            className="bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {saveMutation.isPending ? 'Saving…' : editId ? 'Update' : 'Add Document'}
          </button>
        </form>
      )}

      {/* ── Document list ── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-3 border-b border-slate-100">
          <p className="text-sm font-semibold text-slate-700">All Documents ({docs.length})</p>
        </div>

        {isLoading ? (
          <p className="text-sm text-slate-400 px-5 py-6">Loading…</p>
        ) : docs.length === 0 ? (
          <p className="text-sm text-slate-400 px-5 py-6">No documents added yet. Add your licences and permits to track their expiry.</p>
        ) : (
          <div className="divide-y divide-slate-100">
            {docs.map(doc => (
              <div key={doc.id} className="px-5 py-3 flex items-center justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <p className="text-sm font-medium text-slate-800">{doc.name}</p>
                    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_STYLES[doc.status]}`}>
                      {doc.status.replace('_', ' ')}
                    </span>
                  </div>
                  <div className="flex items-center gap-3 mt-0.5">
                    <p className="text-xs text-slate-400">{doc.docType}</p>
                    <span className="text-xs text-slate-300">·</span>
                    <p className="text-xs text-slate-400">Expires: {fmtDate(doc.expiryDate)}</p>
                    {doc.notes && <><span className="text-xs text-slate-300">·</span><p className="text-xs text-slate-400 truncate">{doc.notes}</p></>}
                  </div>
                </div>
                <div className="flex items-center gap-2 flex-shrink-0">
                  <button
                    onClick={() => startEdit(doc)}
                    className="text-xs text-blue-500 hover:text-blue-700 font-medium"
                  >
                    Edit
                  </button>
                  {isOwner && (
                    <button
                      onClick={() => deleteMutation.mutate(doc.id)}
                      disabled={deleteMutation.isPending}
                      className="text-red-400 hover:text-red-600 text-xs disabled:opacity-50"
                    >
                      ✕
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
