import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { shiftApi } from '../../api/shiftApi'
import { pumpApi } from '../../api/pumpApi'
import { userApi } from '../../api/userApi'
import type { CloseShiftRequest, CreateHandoverRequest, HandoverResponse, Shift, ShiftStatus } from '../../types/shift'
import { formatIstDateTime } from '../../utils/date'
import { Modal } from '../../components/Modal'
import type { ModalTone } from '../../components/Modal'
import { MoneyText } from '../../components/AmountText'
import { parseApiError } from '../../utils/apiError'
import { useConfirmDiscard } from '../../hooks/useConfirmDiscard'
import { readDraft, removeDraft, useSaveDraft, writeDraft } from '../../hooks/useSaveDraft'
import { DiscardChangesDialog } from '../../components/DiscardChangesDialog'
import { DraftRestoredNotice } from '../../components/DraftRestoredNotice'
import { toPaise } from '../../utils/money'
import { attemptFor } from '../../utils/idempotencyKey'
import type { SubmitAttempt } from '../../utils/idempotencyKey'
import { FUEL_UNIT } from '../../constants/fuel'
import { codeLabel } from '../../constants/labels'
import { CreditEntryForm } from './components/CreditEntryForm'
import { emptyNum, fmt } from './components/creditRow'
import { PreRecordedCreditList } from './close/PreRecordedCreditList'
import { Section, ReadingField, PaymentField, BalanceCard } from './close/CloseShiftFields'
import { DiscrepancySection } from './close/DiscrepancySection'
import { CloseShiftFooter } from './close/CloseShiftFooter'
import { LateCloseNotice } from './close/LateCloseNotice'
import { computeCloseShiftTotals } from './close/closeShiftTotals'
import { buildCloseShiftRequest } from './close/closeShiftRequest'
import { useCloseShiftCreditRows } from './close/useCloseShiftCreditRows'
import { HandoverFields } from './close/HandoverFields'
import { EMPTY_HANDOVER_DETAILS } from './close/handoverDetails'
import { closeShiftDraftName, restoredCreditRows } from './close/closeShiftDraft'
import type { CloseShiftDraft } from './close/closeShiftDraft'
import { CloseShiftReview, CloseShiftReviewActions } from './close/CloseShiftReview'
import { CloseShiftSummary, CloseShiftSummaryActions } from './close/CloseShiftSummary'
import { HandoverReportPanel } from './modals/HandoverReportPanel'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'

interface Props {
  shift: Shift
  onClose: () => void
  /** Offered on the summary after a normal close: opens the next shift on the same DU and nozzles. */
  onOpenNextShift?: (duId: number, nozzleIds: number[]) => void
  /** Set to hand the shift over: it is closed and a new shift opens for the incoming operator. */
  handoverActiveShifts?: Shift[]
}

type Step = 'form' | 'review' | 'done'

interface ClosingFigures {
  totalDue: number
  totalCollected: number
  diff: number
  creditEntryCount: number
}

interface ClosedResult {
  closedShift: Shift | null
  status: ShiftStatus | undefined
  incomingOperatorName: string | null
}

const emptyEndReadings = (shift: Shift): Record<number, string> =>
  Object.fromEntries(shift.fuelReadings.map((r) => [r.nozzleId, '']))

export default function CloseShiftModal({ shift, onClose, onOpenNextShift, handoverActiveShifts }: Props) {
  const { t } = useTranslation('shifts')
  const isHandover = handoverActiveShifts !== undefined
  const queryClient = useQueryClient()
  const draftName = closeShiftDraftName(shift.id, isHandover)
  const [restoredDraft, setRestoredDraft] = useState(() => readDraft<CloseShiftDraft>(draftName))
  const [step, setStep] = useState<Step>('form')
  const [serverError, setServerError] = useState<string | null>(null)
  const [noChangeNozzles, setNoChangeNozzles] = useState<Set<number>>(() => new Set(restoredDraft?.values.noChangeNozzleIds))

  /** Per-nozzle end readings: nozzleId → raw string input */
  const [endReadings, setEndReadings] = useState<Record<number, string>>(() =>
    ({ ...emptyEndReadings(shift), ...restoredDraft?.values.endReadings })
  )
  const [cash,              setCash]              = useState(() => restoredDraft?.values.cash ?? '')
  const [upi,               setUpi]               = useState(() => restoredDraft?.values.upi ?? '')
  const [card,              setCard]              = useState(() => restoredDraft?.values.card ?? '')
  const [fleetCard,         setFleetCard]         = useState(() => restoredDraft?.values.fleetCard ?? '')
  const [discrepancyReason, setDiscrepancyReason] = useState(() => restoredDraft?.values.discrepancyReason ?? '')
  const [handoverDetails,   setHandoverDetails]   = useState(() => restoredDraft?.values.handoverDetails ?? EMPTY_HANDOVER_DETAILS)
  const [submitAttempt,     setSubmitAttempt]     = useState<SubmitAttempt | null>(() => restoredDraft?.values.submitAttempt ?? null)
  const [reviewedRequest,   setReviewedRequest]   = useState<CloseShiftRequest | null>(null)
  const [reviewedFigures,   setReviewedFigures]   = useState<ClosingFigures | null>(null)
  const [closedResult,      setClosedResult]      = useState<ClosedResult | null>(null)
  const [printedShift,      setPrintedShift]      = useState<Shift | null>(null)
  const [printError,        setPrintError]        = useState<string | null>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const previousStep = useRef<Step>(step)

  useEffect(() => {
    if (previousStep.current === step) return
    previousStep.current = step
    titleRef.current?.focus()
  }, [step])

  const { data: creditClients = [] } = useQuery({
    queryKey: ['creditClients', shift.pumpId],
    queryFn:  () => pumpApi.getCreditClients(shift.pumpId),
  })

  const { data: pumps = [] } = useQuery({
    queryKey: ['myPumps'],
    queryFn:  pumpApi.getMyPumps,
    staleTime: 5 * 60_000,
  })
  const escalationThreshold = pumps.find((p) => p.id === shift.pumpId)?.discrepancyEscalationThreshold ?? null

  const { data: operators = [] } = useQuery({
    queryKey: ['operators', shift.pumpId],
    queryFn:  () => userApi.getOperators(shift.pumpId),
    enabled:  isHandover,
  })
  const incomingOperatorName = operators.find((o) => o.id === handoverDetails.incomingOperatorId)?.fullName ?? null

  // The operator can add credit entries while this modal is open, so the list is reloaded rather than taken
  // from the snapshot the modal was opened with.
  const { data: latestShift, refetch: reloadShift } = useQuery({
    queryKey: ['shift', shift.pumpId, shift.id],
    queryFn:  () => shiftApi.getShift(shift.pumpId, shift.id),
    initialData: shift,
    initialDataUpdatedAt: 0,
    refetchInterval: 30_000,
    enabled: closedResult === null,
  })

  const availableFuelTypes = shift.fuelReadings.map((r) => r.fuelType)
  const {
    creditRows, expandedRowId, setExpandedRowId, clearCreditRows,
    addCreditRow, updateRow, updateRowMulti, selectClient, removeCreditRow, editRecordedEntry,
  } = useCloseShiftCreditRows(availableFuelTypes, creditClients, restoredCreditRows(restoredDraft?.values))

  // Void state for pre-recorded entries
  const [localVoidedIds,    setLocalVoidedIds]   = useState<Set<number>>(
    () => new Set((shift.creditEntries ?? []).filter(e => e.voidStatus === 'VOIDED').map(e => e.id))
  )

  // Entries that were saved mid-shift via "+ Credit"
  // Exclude voided entries and entries being replaced by an edited row so they don't inflate the subtotal
  const replacedEntryIds = new Set(creditRows.map(r => r.replacesEntryId).filter((id): id is number => id !== null))
  const preRecordedEntries = (latestShift.creditEntries ?? [])
    .filter(e => e.voidStatus !== 'VOIDED' && !localVoidedIds.has(e.id) && !replacedEntryIds.has(e.id))
  const preRecordedTotal   = preRecordedEntries.reduce((s, e) => s + e.amount, 0)

  // nozzleId → nozzleNumber — used to label each meter reading field
  const nozzleNumberById: Record<number, number> = Object.fromEntries(
    shift.nozzles.map((n) => [n.id, n.nozzleNumber])
  )

  // ── Scheduled end time alert ────────────────────────────────────────────────
  // When closing past the shift's defined end, actualEndTime will be snapped back to
  // the scheduled boundary. Warn the operator so they aren't surprised.
  const scheduledEnd = shift.scheduledEndTime ? new Date(shift.scheduledEndTime) : null
  const isLateClose  = scheduledEnd !== null && new Date() > scheduledEnd
  const scheduledEndDisplay = scheduledEnd
    ? formatIstDateTime(scheduledEnd, { hour: '2-digit', minute: '2-digit', hour12: true })
    : null

  // ── Live calculations ───────────────────────────────────────────────────────

  const newCreditTotal = creditRows.reduce((s, r) => s + emptyNum(r.amount), 0)
  const {
    perFuelSales, totalDue, creditTotal, totalCollected, diffPaise, diff,
    readingsComplete, readingsBelowStart, paymentTouched, isBalanced, hasDiscrepancy, canRoundOff,
  } = computeCloseShiftTotals({
    fuelReadings: shift.fuelReadings, endReadings, noChangeNozzles,
    cash, upi, card, fleetCard, preRecordedTotal, newCreditTotal,
  })
  const reasonFilled     = discrepancyReason.trim().length > 0
  const incomingChosen   = !isHandover || handoverDetails.incomingOperatorId !== null
  const canClose         = incomingChosen && (isBalanced || (hasDiscrepancy && reasonFilled))
  const hasUnsavedChanges = Object.values(endReadings).some(v => v !== '')
    || cash !== '' || upi !== '' || card !== '' || fleetCard !== ''
    || creditRows.length > 0 || discrepancyReason.trim() !== ''
    || handoverDetails.incomingOperatorId !== null || handoverDetails.notes.trim() !== ''

  // ── Draft kept on this device ───────────────────────────────────────────────

  const draftValues: CloseShiftDraft = {
    endReadings, noChangeNozzleIds: [...noChangeNozzles], cash, upi, card, fleetCard, discrepancyReason,
    handoverDetails, creditRows, submitAttempt,
  }
  useSaveDraft(draftName, draftValues, hasUnsavedChanges && closedResult === null)

  const discardRestoredEntries = () => {
    removeDraft(draftName)
    setRestoredDraft(null)
    setEndReadings(emptyEndReadings(shift))
    setNoChangeNozzles(new Set())
    setCash('')
    setUpi('')
    setCard('')
    setFleetCard('')
    setDiscrepancyReason('')
    setHandoverDetails(EMPTY_HANDOVER_DETAILS)
    clearCreditRows()
    setSubmitAttempt(null)
    setServerError(null)
  }

  // ── Credit row helpers ──────────────────────────────────────────────────────

  const priceForFuelType = (ft: string): number | null =>
    shift.fuelReadings.find((r) => r.fuelType === ft)?.priceSnapshot ?? null

  const toggleNoChange = (nozzleId: number, startReading: number) => {
    const wasNoChange = noChangeNozzles.has(nozzleId)
    setNoChangeNozzles(prev => {
      const next = new Set(prev)
      if (wasNoChange) next.delete(nozzleId)
      else next.add(nozzleId)
      return next
    })
    setEndReadings(prev => ({ ...prev, [nozzleId]: wasNoChange ? '' : String(startReading) }))
  }

  // ── Submit ──────────────────────────────────────────────────────────────────

  const finishClosing = (result: ClosedResult) => {
    removeDraft(draftName)
    queryClient.invalidateQueries({ queryKey: ['activeShifts', shift.pumpId] })
    queryClient.invalidateQueries({ queryKey: ['shiftHistory', shift.pumpId] })
    setClosedResult(result)
    setStep('done')
  }

  const showSubmitError = (fallback: string) => (err: unknown) => {
    setServerError(parseApiError(err, fallback))
    reloadShift()
  }

  const closeMutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: CloseShiftRequest; idempotencyKey: string }) =>
      shiftApi.closeShift(shift.pumpId, shift.id, request, idempotencyKey),
    onSuccess: (closedShift) =>
      finishClosing({ closedShift, status: closedShift?.status, incomingOperatorName: null }),
    onError: showSubmitError(t('close.errors.closeFailed')),
  })

  const handoverMutation = useMutation({
    mutationFn: ({ request, idempotencyKey }: { request: CreateHandoverRequest; idempotencyKey: string }) =>
      shiftApi.createHandover(shift.pumpId, request, idempotencyKey),
    onSuccess: (handover: HandoverResponse) => finishClosing({
      closedShift: null,
      status: handover?.outgoingShiftStatus,
      incomingOperatorName: handover?.incomingOperatorName ?? incomingOperatorName,
    }),
    onError: showSubmitError(t('close.errors.handoverFailed')),
  })
  const isSubmitting = closeMutation.isPending || handoverMutation.isPending

  const printMutation = useMutation({
    mutationFn: () => shiftApi.getShift(shift.pumpId, shift.id),
    onSuccess: setPrintedShift,
    onError: (err: unknown) => setPrintError(parseApiError(err, t('close.errors.reportLoadFailed'))),
  })

  const handleReview = (e: React.FormEvent) => {
    e.preventDefault()
    setServerError(null)
    if (!canClose) return

    const result = buildCloseShiftRequest({
      fuelReadings: shift.fuelReadings, endReadings, readingsBelowStart, creditRows,
      cash, upi, card, fleetCard, creditTotal, discrepancyReason,
    })
    if ('error' in result) {
      setServerError(result.error)
      return
    }
    setReviewedRequest(result.request)
    setReviewedFigures({ totalDue, totalCollected, diff, creditEntryCount: preRecordedEntries.length + creditRows.length })
    setStep('review')
  }

  // The key is saved with the draft before sending, so a resend after a timeout (even after a reload) reuses it.
  const keyFor = (request: unknown) => {
    const attempt = attemptFor(submitAttempt, request)
    setSubmitAttempt(attempt)
    writeDraft(draftName, { ...draftValues, submitAttempt: attempt })
    return attempt.key
  }

  const confirmClose = () => {
    if (!reviewedRequest) return
    setServerError(null)
    if (isHandover && handoverDetails.incomingOperatorId !== null) {
      const request: CreateHandoverRequest = {
        outgoingShiftId: shift.id,
        incomingOperatorId: handoverDetails.incomingOperatorId,
        physicalCashVerified: handoverDetails.physicalCashVerified,
        meterReadingsVerified: handoverDetails.meterReadingsVerified,
        notes: handoverDetails.notes.trim() || undefined,
        closing: reviewedRequest,
      }
      handoverMutation.mutate({ request, idempotencyKey: keyFor(request) })
      return
    }
    closeMutation.mutate({ request: reviewedRequest, idempotencyKey: keyFor(reviewedRequest) })
  }

  const backToForm = () => {
    setServerError(null)
    setStep('form')
  }

  const printReport = () => {
    setPrintError(null)
    if (closedResult?.closedShift?.id) setPrintedShift(closedResult.closedShift)
    else printMutation.mutate()
  }

  const handleRoundOff = () => {
    const newCashPaise = toPaise(emptyNum(cash)) - diffPaise
    if (newCashPaise < 0) {
      setServerError(t('close.errors.roundOffNegative'))
      return
    }
    setCash(newCashPaise === 0 ? '' : (newCashPaise / 100).toFixed(2))
  }

  const restInCashPaise = Math.max(0,
    toPaise(totalDue) - toPaise(emptyNum(upi)) - toPaise(emptyNum(card)) - toPaise(emptyNum(fleetCard)) - toPaise(creditTotal))

  const closeAndForget = () => {
    removeDraft(draftName)
    onClose()
  }
  const { requestClose, isConfirming, discard, keepEditing } = useConfirmDiscard(hasUnsavedChanges, closeAndForget)

  // Stays neutral until readings and payments are in; an empty form is not a shortage.
  const totalsEntered = readingsComplete && paymentTouched
  const headerTone: ModalTone = !totalsEntered
    ? 'neutral'
    : isBalanced
    ? 'success'
    : hasDiscrepancy
    ? diff < 0 ? 'danger' : 'warning'
    : 'neutral'

  const title = step === 'review'
    ? isHandover ? t('close.title.reviewHandover') : t('close.title.reviewClose')
    : step === 'done'
    ? isHandover ? t('close.title.handedOver') : t('close.title.closed')
    : isHandover ? t('close.title.handover') : t('close.title.close')

  const dismiss = step === 'done' ? onClose : step === 'review' ? backToForm : requestClose

  if (printedShift) {
    return <HandoverReportPanel shift={printedShift} onClose={() => setPrintedShift(null)} />
  }

  const footer = step === 'form' ? (
    <CloseShiftFooter
      readingsComplete={readingsComplete}
      paymentTouched={paymentTouched}
      isBalanced={isBalanced}
      reasonFilled={reasonFilled}
      isHandover={isHandover}
      incomingChosen={incomingChosen}
      onCancel={requestClose}
    />
  ) : step === 'review' && reviewedFigures ? (
    <CloseShiftReviewActions isHandover={isHandover} isSubmitting={isSubmitting} onConfirm={confirmClose} onEdit={backToForm} />
  ) : step === 'done' && reviewedFigures && closedResult ? (
    <CloseShiftSummaryActions
      isPreparingPrint={printMutation.isPending}
      onPrint={printReport}
      onOpenNextShift={onOpenNextShift && !isHandover
        ? () => onOpenNextShift(shift.duId, shift.nozzles.map((n) => n.id))
        : undefined}
      onDone={onClose}
    />
  ) : null

  return (
    <>
    <Modal
      title={title}
      subtitle={t('close.subtitle', { nozzles: shift.nozzles.map((n) => `#${n.nozzleNumber}`).join(', '), operator: shift.operatorName, window: shift.shiftWindow })}
      tone={headerTone}
      size="lg"
      onClose={dismiss}
      canClose={!isSubmitting}
      titleRef={titleRef}
      fullScreenOnPhone={step !== 'form'}
      headerExtra={step === 'form' && (
        <div className="flex flex-wrap gap-2 mt-3">
          {shift.fuelReadings.map((r) => (
            <span key={r.nozzleId}
              className="bg-surface-sunken text-ink-2 text-xs font-medium px-2.5 py-1 rounded-badge">
              {codeLabel(r.fuelType)} ₹{r.priceSnapshot}/{FUEL_UNIT[r.fuelType] ?? 'L'}
            </span>
          ))}
        </div>
      )}
      onSubmit={step === 'form' ? handleReview : undefined}
      bodyClassName={step === 'form' ? 'space-y-5' : step === 'review' ? 'space-y-5 text-sm text-ink' : 'space-y-4 text-sm text-ink'}
      footer={footer}
      footerClassName={step === 'form'
        ? 'flex-col-reverse sm:flex-row bg-surface-sunken'
        : step === 'review' ? 'ui-modal-footer--large' : 'ui-modal-footer--large grid grid-cols-2'}
    >
        {step === 'review' && reviewedFigures && (
          <CloseShiftReview
            isHandover={isHandover}
            incomingOperatorName={incomingOperatorName}
            lines={perFuelSales.map(({ reading, sold, value }) => ({
              nozzleId: reading.nozzleId,
              label: t('close.nozzleLine', { number: nozzleNumberById[reading.nozzleId] ?? '?', fuel: codeLabel(reading.fuelType) }),
              units: sold,
              unit: FUEL_UNIT[reading.fuelType] ?? 'L',
              amount: value,
            }))}
            totalDue={reviewedFigures.totalDue}
            payments={{ cash: emptyNum(cash), upi: emptyNum(upi), card: emptyNum(card), fleetCard: emptyNum(fleetCard), credit: creditTotal }}
            creditEntryCount={reviewedFigures.creditEntryCount}
            totalCollected={reviewedFigures.totalCollected}
            diff={reviewedFigures.diff}
            discrepancyReason={discrepancyReason}
            escalationThreshold={escalationThreshold}
            serverError={serverError}
          />
        )}

        {step === 'done' && reviewedFigures && closedResult && (
          <CloseShiftSummary
            incomingOperatorName={closedResult.incomingOperatorName}
            totalDue={reviewedFigures.totalDue}
            totalCollected={reviewedFigures.totalCollected}
            diff={reviewedFigures.diff}
            creditEntryCount={reviewedFigures.creditEntryCount}
            isEscalated={closedResult.status === 'CLOSED_DISCREPANCY_PENDING_APPROVAL'}
            printError={printError}
          />
        )}

        {step === 'form' && (
          <>

            {restoredDraft && (
              <DraftRestoredNotice savedAt={restoredDraft.savedAt} onDiscard={discardRestoredEntries} />
            )}

            {/* ── Late-close notice ─────────────────────────────────────── */}
            {isLateClose && scheduledEndDisplay && (
              <LateCloseNotice scheduledEndDisplay={scheduledEndDisplay} />
            )}

            {isHandover && handoverActiveShifts && (
              <HandoverFields
                shift={shift}
                activeShifts={handoverActiveShifts}
                details={handoverDetails}
                onChange={setHandoverDetails}
              />
            )}

            {/* ── Step 1: End Readings ──────────────────────────────────── */}
            <Section label="1" title={t('close.sections.endReadings')}>
              <FormGrid>
                {perFuelSales.map(({ reading, sold, isBelowStart }, index) => {
                  const isNoChange = noChangeNozzles.has(reading.nozzleId)
                  const endVal = endReadings[reading.nozzleId] ?? ''
                  const isInvalid = isBelowStart
                  return (
                    <ReadingField
                      key={reading.nozzleId}
                      label={t('close.endReadingLabel', { number: nozzleNumberById[reading.nozzleId] ?? '?', fuel: codeLabel(reading.fuelType), unit: FUEL_UNIT[reading.fuelType] ?? 'L' })}
                      startValue={reading.startReading}
                      soldUnits={sold}
                      pricePerUnit={reading.priceSnapshot}
                      value={endVal}
                      onChange={(v) => setEndReadings((prev) => ({ ...prev, [reading.nozzleId]: v }))}
                      unitLabel={FUEL_UNIT[reading.fuelType] ?? 'L'}
                      isInvalid={isInvalid}
                      noChange={isNoChange}
                      onNoChangeToggle={() => toggleNoChange(reading.nozzleId, reading.startReading)}
                      focusOnOpen={index === 0}
                    />
                  )
                })}
              </FormGrid>
            </Section>

            {/* ── Total due card ─────────────────────────────────────────── */}
            <div className="bg-info-bg border border-info rounded-card p-4 flex items-center justify-between">
              <div>
                <p className="text-xs text-info font-medium uppercase tracking-wide">{t('close.totalDue')}</p>
                <p className="text-xs text-ink-2 mt-0.5">{t('close.totalDueHint')}</p>
              </div>
              <p className="text-2xl font-bold text-info"><MoneyText value={totalDue} /></p>
            </div>

            {/* ── Step 2: Credit Sales ─────────────────────────────────── */}
            <Section label="2" title={t('close.sections.creditSales')} optional>
              <div className="border border-border rounded-card overflow-visible">

                {/* Pre-recorded entries (saved mid-shift) — with edit / delete */}
                {preRecordedEntries.length > 0 && (
                  <PreRecordedCreditList
                    shift={shift}
                    entries={preRecordedEntries}
                    total={preRecordedTotal}
                    onEdit={editRecordedEntry}
                    onVoided={(entryId) => setLocalVoidedIds(prev => new Set([...prev, entryId]))}
                  />
                )}

                <div className="flex items-center justify-between px-4 py-2.5 bg-warn-bg">
                  <div>
                    {creditRows.length > 0 ? (
                      <span className="text-xs text-warn font-medium">
                        {t('close.newEntries', { count: creditRows.length, amount: fmt(newCreditTotal) })}
                      </span>
                    ) : (
                      <span className="text-xs text-ink-3">
                        {preRecordedEntries.length > 0 ? t('close.addMoreBelow') : t('close.noCredit')}
                      </span>
                    )}
                  </div>
                  <button type="button" onClick={addCreditRow}
                    className="ui-btn ui-btn-warning min-h-0 px-3 py-1.5 text-xs">
                    {t('close.addEntry')}
                  </button>
                </div>

                {creditRows.length > 0 && (
                  <div className="divide-y divide-border">
                    {creditRows.map((row, idx) => (
                      <CreditEntryForm
                        key={row.id}
                        row={row}
                        rowNumber={idx + 1}
                        isExpanded={expandedRowId === row.id}
                        onExpandedChange={setExpandedRowId}
                        creditClients={creditClients}
                        availableFuelTypes={availableFuelTypes}
                        priceForFuelType={priceForFuelType}
                        onUpdate={(field, value) => updateRow(row.id, field, value)}
                        onUpdateMulti={(updates) => updateRowMulti(row.id, updates)}
                        onSelectClient={(clientId, clientName) => selectClient(row.id, clientId, clientName)}
                        onRemove={() => removeCreditRow(row.id)}
                      />
                    ))}
                  </div>
                )}
              </div>
            </Section>

            {/* ── Step 3: Payment Collected ────────────────────────────── */}
            <Section label="3" title={t('close.sections.payment')}>
              <FormGrid>
                <FormGridFullRow className="flex flex-col gap-2 sm:flex-row sm:items-end">
                  <div className="flex-1 min-w-0">
                    <PaymentField label={t('close.payment.cash')} value={cash} onChange={setCash} />
                  </div>
                  <button type="button" onClick={() => setCash((restInCashPaise / 100).toFixed(2))}
                    className="ui-btn ui-btn-secondary">
                    {t('close.payment.restInCash', { amount: fmt(restInCashPaise / 100) })}
                  </button>
                </FormGridFullRow>
                <PaymentField label={t('close.payment.upi')} value={upi}       onChange={setUpi} />
                <PaymentField label={t('close.payment.card')} value={card}      onChange={setCard} />
                <PaymentField label={t('close.payment.fleetCard')} value={fleetCard} onChange={setFleetCard} />
              </FormGrid>
              {creditTotal > 0 && (
                <div className="flex items-center justify-between bg-warn-bg border border-warn rounded-control px-3 py-2 mt-2">
                  <span className="text-xs font-medium text-warn">{t('close.payment.creditTotal')}</span>
                  <span className="text-sm font-bold text-warn-strong"><MoneyText value={creditTotal} /></span>
                </div>
              )}
            </Section>

            {/* ── Balance status card ───────────────────────────────────── */}
            {paymentTouched && readingsComplete && (
              <BalanceCard totalDue={totalDue} totalCollected={totalCollected} diff={diff} />
            )}

            {/* ── Discrepancy reason ────────────────────────────────────── */}
            {hasDiscrepancy && (
              <DiscrepancySection
                diff={diff}
                canRoundOff={canRoundOff}
                onRoundOff={handleRoundOff}
                reason={discrepancyReason}
                onReasonChange={setDiscrepancyReason}
              />
            )}

            {serverError && (
              <div className="bg-bad-bg border border-bad rounded-card p-3">
                <p role="alert" className="text-bad text-sm">{serverError}</p>

              </div>
            )}
          </>
        )}
    </Modal>
    {isConfirming && <DiscardChangesDialog onDiscard={discard} onKeepEditing={keepEditing} />}
    </>
  )
}
