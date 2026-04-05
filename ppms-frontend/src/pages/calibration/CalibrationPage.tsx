import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pumpApi } from '../../api/pumpApi'
import { usePumpStore } from '../../store/usePumpStore'
import { calibrationApi } from '../../api/calibrationApi'
import type { LogCalibrationRequest } from '../../api/calibrationApi'
import { SearchableSelect } from '../../components/SearchableSelect'
import { Pagination } from '../../components/Pagination'

function fmtDate(d: string) {
  return new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })
}

export default function CalibrationPage() {
  const qc = useQueryClient()

  const { selectedPumpId: pumpId } = usePumpStore()

  const { data: nozzles = [] } = useQuery({
    queryKey:  ['nozzles', pumpId],
    queryFn:   () => pumpApi.getNozzles(pumpId!),
    enabled:   !!pumpId,
  })

  const [selectedNozzleId, setSelectedNozzleId] = useState<number>(0)

  // History filter — independent from the form nozzle selector
  const [historyNozzleId, setHistoryNozzleId] = useState<number>(0)
  const [historyPage, setHistoryPage] = useState(0)
  const [historyPageSize, setHistoryPageSize] = useState(10)

  const { data: calibrationsPage, isLoading } = useQuery({
    queryKey:  ['calibrations', pumpId, historyNozzleId, historyPage, historyPageSize],
    queryFn:   () => calibrationApi.getCalibrations(pumpId!, historyNozzleId, historyPage, historyPageSize),
    enabled:   !!pumpId && !!historyNozzleId,
  })

  const [form, setForm] = useState<LogCalibrationRequest>({
    calibrationDate: new Date().toISOString().slice(0, 10),
    nextCalibrationDue: null,
    calibratedBy: null,
    certificateReference: null,
    notes: null,
  })
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (data: LogCalibrationRequest) =>
      calibrationApi.recordCalibration(pumpId!, selectedNozzleId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['calibrations', pumpId, selectedNozzleId] })
      setForm({ calibrationDate: new Date().toISOString().slice(0, 10), nextCalibrationDue: null, calibratedBy: null, certificateReference: null, notes: null })
      setFormError(null)
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to record calibration'),
  })

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedNozzleId) { setFormError('Please select a nozzle'); return }
    createMutation.mutate(form)
  }

  const selectedNozzle = nozzles.find((n: any) => n.id === selectedNozzleId)
  const historyNozzle  = nozzles.find((n: any) => n.id === historyNozzleId)
  const calibrations   = calibrationsPage?.content ?? []

  return (
    <div className="p-6 space-y-5 w-full lg:max-w-[85%] 2xl:max-w-[60%]">

      {/* ── Header ── */}
      <div>
        <h1 className="text-xl font-bold text-slate-800">Nozzle Calibration</h1>
        <p className="text-sm text-slate-500 mt-0.5">Record periodic calibration checks for regulatory compliance</p>
      </div>

      {/* ── Record form ── */}
      <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-slate-200 px-5 py-4 space-y-3">
        <p className="text-sm font-semibold text-slate-700">Record Calibration</p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label className="text-xs text-slate-500 block mb-1">Nozzle</label>
            <SearchableSelect
              value={selectedNozzleId ? selectedNozzleId.toString() : ''}
              onChange={v => { setSelectedNozzleId(parseInt(v) || 0); setFormError(null) }}
              placeholder="Select nozzle…"
              options={nozzles.map((n: any) => ({
                value: n.id.toString(),
                label: `Nozzle #${n.nozzleNumber} — ${n.outlets?.map((o: any) => o.fuelType).join(' / ') ?? ''}`,
              }))}
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Calibration Date</label>
            <input
              type="date"
              value={form.calibrationDate}
              onChange={e => setForm(f => ({ ...f, calibrationDate: e.target.value }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              required
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Next Calibration Due (optional)</label>
            <input
              type="date"
              value={form.nextCalibrationDue ?? ''}
              onChange={e => setForm(f => ({ ...f, nextCalibrationDue: e.target.value || null }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Calibrated By</label>
            <input
              type="text"
              value={form.calibratedBy ?? ''}
              onChange={e => setForm(f => ({ ...f, calibratedBy: e.target.value || null }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="Technician or agency name"
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Certificate Reference (optional)</label>
            <input
              type="text"
              value={form.certificateReference ?? ''}
              onChange={e => setForm(f => ({ ...f, certificateReference: e.target.value || null }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="Inspection certificate number"
            />
          </div>

          <div>
            <label className="text-xs text-slate-500 block mb-1">Notes (optional)</label>
            <input
              type="text"
              value={form.notes ?? ''}
              onChange={e => setForm(f => ({ ...f, notes: e.target.value || null }))}
              className="w-full text-sm border border-slate-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-400"
              placeholder="Additional observations"
            />
          </div>
        </div>

        {formError && <p className="text-xs text-red-600">{formError}</p>}

        <button
          type="submit"
          disabled={createMutation.isPending}
          className="bg-blue-600 text-white text-sm font-medium px-4 py-2 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {createMutation.isPending ? 'Saving…' : 'Record Calibration'}
        </button>
      </form>

      {/* ── Calibration history ── */}
      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        <div className="px-5 py-3 border-b border-slate-100 flex flex-wrap items-center gap-3">
          <p className="text-sm font-semibold text-slate-700 mr-auto">
            Calibration History
            {historyNozzle && (
              <span className="text-slate-400 font-normal ml-1">
                — Nozzle #{historyNozzle.nozzleNumber} ({historyNozzle.outlets?.map((o: any) => o.fuelType).join(' / ')})
              </span>
            )}
          </p>

          <div className="w-56">
            <SearchableSelect
              value={historyNozzleId ? historyNozzleId.toString() : ''}
              onChange={v => { setHistoryNozzleId(parseInt(v) || 0); setHistoryPage(0) }}
              placeholder="Filter by nozzle…"
              options={nozzles.map((n: any) => ({
                value: n.id.toString(),
                label: `Nozzle #${n.nozzleNumber} — ${n.outlets?.map((o: any) => o.fuelType).join(' / ') ?? ''}`,
              }))}
              size="sm"
            />
          </div>
        </div>

        {!historyNozzleId ? (
          <p className="text-sm text-slate-400 px-5 py-6">Select a nozzle to view its calibration history.</p>
        ) : isLoading ? (
          <p className="text-sm text-slate-400 px-5 py-6">Loading…</p>
        ) : calibrations.length === 0 ? (
          <p className="text-sm text-slate-400 px-5 py-6">No calibration records for this nozzle yet.</p>
        ) : (
          <>
            <div className="divide-y divide-slate-100">
              {calibrations.map(c => (
                <div key={c.id} className="px-5 py-3 space-y-1">
                  <div className="flex items-center justify-between">
                    <p className="text-sm font-medium text-slate-800">{fmtDate(c.calibrationDate)}</p>
                    {c.nextCalibrationDue && (
                      <span className="text-xs bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full font-medium">
                        Next due: {fmtDate(c.nextCalibrationDue)}
                      </span>
                    )}
                  </div>
                  <div className="flex flex-wrap gap-4 text-xs text-slate-500">
                    {c.calibratedBy && <span>By: {c.calibratedBy}</span>}
                    {c.certificateReference && <span>Cert: {c.certificateReference}</span>}
                    <span className="text-slate-400">Logged by: {c.loggedByName}</span>
                  </div>
                  {c.notes && <p className="text-xs text-slate-400">{c.notes}</p>}
                </div>
              ))}
            </div>
            {calibrationsPage && (
              <div className="px-5">
                <Pagination
                  data={calibrationsPage}
                  onPageChange={p => setHistoryPage(p)}
                  onPageSizeChange={s => { setHistoryPageSize(s); setHistoryPage(0) }}
                  pageSizeOptions={[10, 20, 50]}
                />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
