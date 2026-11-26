import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { ShortageClaim, ShortageClaimNozzleReading } from '../../../api/inventoryApi'
import { LitresText, MoneyText } from '../../../components/AmountText'
import { DataTable } from '../../../components/DataTable'
import type { DataTableColumn } from '../../../components/DataTable'
import { codeLabel } from '../../../constants/labels'
import { formatDecimal, formatWithSign } from '../../../utils/format'
import { fmtDate } from '../inventoryFormat'
import { ClaimStatusBadge } from './ClaimStatusBadge'

function Figures({ title, rows }: { title: string; rows: { label: string; value: ReactNode; isResult?: boolean }[] }) {
  return (
    <section className="space-y-2 break-inside-avoid">
      <h3 className="text-sm font-semibold text-ink">{title}</h3>
      <dl className="divide-y divide-border rounded-card border border-border">
        {rows.map(row => (
          <div key={row.label} className={`flex justify-between gap-3 px-3 py-2 ${row.isResult ? 'font-semibold text-ink' : 'text-ink-2'}`}>
            <dt>{row.label}</dt>
            <dd className="text-right tabular-nums">{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function density(value: number | null): string {
  return value === null ? '—' : `${formatDecimal(value, 1)} kg/m³`
}

/** Every figure of a shortage claim, laid out to print as an A4 claim note with signature lines. */
export function ClaimNoteBody({ claim }: { claim: ShortageClaim }) {
  const { t } = useTranslation('inventory')
  const readingColumns: DataTableColumn<ShortageClaimNozzleReading>[] = [
    {
      key: 'nozzle', header: t('claimNote.nozzle'),
      cell: r => r.duName ? t('claimNote.nozzleWithDu', { number: r.nozzleNumber, du: r.duName }) : t('claimNote.nozzleNumber', { number: r.nozzleNumber }),
    },
    { key: 'before', header: t('claimNote.readingBefore'), numeric: true, cell: r => formatDecimal(r.readingBefore, 3) },
    { key: 'after', header: t('claimNote.readingAfter'), numeric: true, cell: r => formatDecimal(r.readingAfter, 3) },
    { key: 'sold', header: t('claimNote.litresSold'), numeric: true, cell: r => <LitresText value={r.litresSold} /> },
  ]

  return (
    <div className="space-y-5 text-sm">
      {claim.pump && (
        <header className="hidden print:block text-center">
          <p className="text-lg font-bold text-ink">{claim.pump.legalName ?? claim.pump.name}</p>
          <p className="text-xs text-ink-2">{claim.pump.address}</p>
          {claim.pump.gstin && <p className="text-xs text-ink-2">{t('claimNote.gstin', { gstin: claim.pump.gstin })}</p>}
          <h2 className="mt-3 text-base font-semibold text-ink">{t('claimNote.title', { id: claim.id })}</h2>
        </header>
      )}

      <dl className="grid grid-cols-2 gap-3">
        {[
          { label: t('claimNote.invoice'), value: claim.invoiceReference },
          { label: t('claimNote.deliveryDate'), value: fmtDate(claim.deliveryDate) },
          { label: t('claimNote.tank'), value: `${claim.tankIdentifier} · ${codeLabel(claim.fuelType)}` },
          { label: t('claimNote.tanker'), value: claim.tankerName ?? '—' },
          { label: t('claimNote.raised'), value: t('claimNote.raisedBy', { date: fmtDate(claim.raisedAt), name: claim.raisedByUserName }) },
          { label: t('claimNote.status'), value: <ClaimStatusBadge status={claim.status} /> },
        ].map(({ label, value }) => (
          <div key={label}>
            <dt className="text-xs text-ink-3">{label}</dt>
            <dd className="font-semibold text-ink">{value}</dd>
          </div>
        ))}
      </dl>

      <Figures title={t('claimNote.quantityTitle')} rows={[
        { label: t('claimNote.invoiceLitres'), value: <LitresText value={claim.invoiceLitres} /> },
        { label: t('claimNote.dipBefore'), value: <LitresText value={claim.dipBeforeLitres} /> },
        { label: t('claimNote.dipAfter'), value: <LitresText value={claim.dipAfterLitres} /> },
        { label: t('claimNote.soldBetween'), value: <LitresText value={claim.soldBetweenLitres} /> },
        { label: t('claimNote.received'), value: <LitresText value={claim.receivedLitres} /> },
        {
          label: t('claimNote.short', { percent: formatDecimal(claim.shortPercent), tolerance: formatDecimal(claim.tolerancePercent) }),
          value: <LitresText value={claim.shortLitres} />, isResult: true,
        },
        { label: t('claimNote.pricePerLitre'), value: <MoneyText value={claim.pricePerLitre} /> },
        { label: t('claims.claimAmount'), value: <MoneyText value={claim.claimAmount} />, isResult: true },
      ]} />

      <Figures title={t('claimNote.densityTitle')} rows={[
        { label: t('claimNote.observedDensity'), value: density(claim.observedDensity) },
        { label: t('claimNote.invoiceDensity'), value: density(claim.invoiceDensity) },
        {
          label: t('claimNote.densityDifference', { tolerance: formatDecimal(claim.densityTolerance, 1) }),
          value: claim.densityDifference === null ? '—'
            : `${formatWithSign(claim.densityDifference, v => formatDecimal(v, 1))} kg/m³${claim.densityFlagged ? ` · ${t('checkStatus.densityFlag')}` : ''}`,
          isResult: claim.densityFlagged,
        },
      ]} />

      {claim.nozzleReadings && claim.nozzleReadings.length > 0 && (
        <DataTable caption={t('claimNote.readingsTitle')} columns={readingColumns} rows={claim.nozzleReadings}
          rowKey={r => r.nozzleId} tableClassName="text-xs" />
      )}

      {claim.status === 'SETTLED' && (
        <Figures title={t('claimNote.settlementTitle')} rows={[
          { label: t('settleClaim.amount'), value: <MoneyText value={claim.creditNoteAmount} />, isResult: true },
          { label: t('settleClaim.reference'), value: claim.creditNoteReference ?? '—' },
          { label: t('settleClaim.date'), value: claim.creditNoteDate ? fmtDate(claim.creditNoteDate) : '—' },
          { label: t('claimNote.resolvedBy'), value: claim.resolvedByUserName ?? '—' },
        ]} />
      )}
      {claim.status === 'REJECTED' && (
        <Figures title={t('claimNote.rejectionTitle')} rows={[
          { label: t('rejectClaim.reason'), value: claim.rejectionReason ?? '—' },
          { label: t('claimNote.resolvedBy'), value: claim.resolvedByUserName ?? '—' },
        ]} />
      )}

      <div className="hidden print:grid grid-cols-2 gap-10 pt-10">
        <div>
          <div className="border-b border-control mb-1" />
          <p className="text-xs text-ink-3">{t('claimNote.pumpSignature')}</p>
        </div>
        <div>
          <div className="border-b border-control mb-1" />
          <p className="text-xs text-ink-3">{t('claimNote.supplierSignature')}</p>
        </div>
      </div>
    </div>
  )
}
