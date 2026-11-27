import client from './client'

export type RadarDays = 30 | 90

export type RadarBand = 'LOW' | 'WATCH' | 'HIGH' | 'NOT_ENOUGH_DATA'

export type EvidenceKind =
  | 'SHORT_SHIFT'
  | 'CREDIT_VOID'
  | 'CREDIT_REASSIGNED'
  | 'SETTLEMENT_VOID'
  | 'CASH_DROP_REJECTED'
  | 'EXPENSE_REJECTED'
  | 'METER_AMENDMENT'
  | 'READING_JUMP'
  | 'SALES_OUTLIER'
  | 'LOSS_WEEK'

/** One rule of a score: the points it gave out of its most, and the figures it used. */
export interface ScoreReason {
  code: string
  points: number
  maxPoints: number
  params: Record<string, string | number>
}

/** One event behind a score; fields that do not apply to its kind are null. */
export interface RadarEvidence {
  kind: EvidenceKind
  date: string
  amount: number | null
  litres: number | null
  usualLitres: number | null
  percent: number | null
  fromReading: number | null
  toReading: number | null
  shiftId: number | null
  /** The settlement, expense, cash drop, credit client, amendment or previous shift the event is about. */
  referenceId: number | null
  note: string | null
}

interface ScoredSubject {
  score: number
  band: RadarBand
  reasons: ScoreReason[]
  evidence: RadarEvidence[]
}

export interface PersonScore extends ScoredSubject {
  userId: number
  name: string
  role: string
  shiftCount: number
  shortShiftCount: number
  shortAmount: number
  trend: 'RISING' | 'STEADY' | 'FALLING'
  correctionCount: number
}

export interface NozzleScore extends ScoredSubject {
  nozzleId: number
  nozzleNumber: number
  duName: string
  fuelType: string
  shiftCount: number
  amendmentCount: number
  readingJumpCount: number
  salesOutlierCount: number
}

export interface WeekFigure {
  weekStart: string
  weekEnd: string
  soldLitres: number
  varianceLitres: number
  variancePercent: number | null
  lossAboveLimit: boolean
}

export interface TankScore extends ScoredSubject {
  tankId: number
  tankIdentifier: string
  fuelType: string
  lossAlertPercent: number
  varianceLitres: number | null
  variancePercent: number | null
  weeks: WeekFigure[]
}

export interface LeakageRadar {
  periodDays: RadarDays
  from: string
  to: string
  minimumShifts: number
  people: PersonScore[]
  nozzles: NozzleScore[]
  tanks: TankScore[]
}

export const radarApi = {
  get: (pumpId: number, days: RadarDays) =>
    client.get<LeakageRadar>(`/pumps/${pumpId}/leakage-radar`, { params: { days } }).then(r => r.data),
}
