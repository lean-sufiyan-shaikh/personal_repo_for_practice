package com.ppms.radar;

import com.ppms.cash.CashDropStatus;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** The radar's reads: each loads one kind of event for a whole pump and period in a single statement. */
public interface LeakageRadarRepository extends Repository<Shift, Long> {

    @Query("""
            SELECT new com.ppms.radar.ShiftOutcomeRow(s.id, s.operatorId, s.shiftDate, s.discrepancyType, s.discrepancyAmount)
            FROM Shift s
            WHERE s.pumpId = :pumpId AND s.shiftDate BETWEEN :from AND :to AND s.status IN :statuses
            """)
    List<ShiftOutcomeRow> findShiftOutcomes(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                            @Param("to") LocalDate to,
                                            @Param("statuses") Collection<ShiftStatus> statuses);

    @Query("""
            SELECT new com.ppms.radar.NozzleShiftRow(r.nozzleId, s.id, s.shiftDate, s.shiftDefinitionId,
                   s.actualStartTime, s.actualEndTime, r.startReading, r.endReading, r.unitsSold)
            FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId AND s.shiftDate BETWEEN :from AND :to
              AND s.status IN :statuses AND r.endReading IS NOT NULL AND r.unitsSold IS NOT NULL
            ORDER BY r.nozzleId, s.actualStartTime, s.id
            """)
    List<NozzleShiftRow> findNozzleShifts(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                          @Param("to") LocalDate to,
                                          @Param("statuses") Collection<ShiftStatus> statuses);

    @Query("""
            SELECT new com.ppms.radar.CorrectionRow(e.voidedByUserId, e.voidedAt, e.amount, e.shiftId, e.id, e.voidReason)
            FROM ShiftCreditEntry e, Shift s
            WHERE e.shiftId = s.id AND s.pumpId = :pumpId AND e.voidStatus = 'VOIDED'
              AND e.voidedByUserId IS NOT NULL AND e.voidedAt >= :start AND e.voidedAt < :end
            """)
    List<CorrectionRow> findCreditVoids(@Param("pumpId") Long pumpId, @Param("start") OffsetDateTime start,
                                        @Param("end") OffsetDateTime end);

    @Query("""
            SELECT new com.ppms.radar.CorrectionRow(r.reassignedByUserId, r.reassignedAt, e.amount, e.shiftId,
                   r.toClientId, r.reason)
            FROM CreditEntryReassignment r, ShiftCreditEntry e, Shift s
            WHERE r.creditEntryId = e.id AND e.shiftId = s.id AND s.pumpId = :pumpId
              AND r.reassignedAt >= :start AND r.reassignedAt < :end
            """)
    List<CorrectionRow> findCreditReassignments(@Param("pumpId") Long pumpId, @Param("start") OffsetDateTime start,
                                                @Param("end") OffsetDateTime end);

    @Query("""
            SELECT new com.ppms.radar.CorrectionRow(p.voidedByUserId, p.voidedAt, p.amountReceived, p.id, p.voidReason)
            FROM PaymentSettlement p
            WHERE p.pumpId = :pumpId AND p.voidStatus = 'VOIDED'
              AND p.voidedByUserId IS NOT NULL AND p.voidedAt >= :start AND p.voidedAt < :end
            """)
    List<CorrectionRow> findSettlementVoids(@Param("pumpId") Long pumpId, @Param("start") OffsetDateTime start,
                                            @Param("end") OffsetDateTime end);

    @Query("""
            SELECT new com.ppms.radar.CorrectionRow(d.operatorId, d.reviewedAt, d.amount, d.shiftId, d.id,
                   d.rejectionReason)
            FROM CashDrop d
            WHERE d.pumpId = :pumpId AND d.status = :status AND d.reviewedAt >= :start AND d.reviewedAt < :end
            """)
    List<CorrectionRow> findCashDropsWithStatus(@Param("pumpId") Long pumpId, @Param("status") CashDropStatus status,
                                                @Param("start") OffsetDateTime start,
                                                @Param("end") OffsetDateTime end);

    @Query("""
            SELECT new com.ppms.radar.CorrectionRow(x.recordedByUserId, x.approvedAt, x.amount, x.id, x.approvalNotes)
            FROM PumpExpense x
            WHERE x.pumpId = :pumpId AND x.approvalStatus = :status AND x.approvedAt >= :start AND x.approvedAt < :end
            """)
    List<CorrectionRow> findExpensesReviewedAs(@Param("pumpId") Long pumpId, @Param("status") ExpenseApprovalStatus status,
                                               @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);
}
