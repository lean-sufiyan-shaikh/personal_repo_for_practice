package com.ppms.cash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CashEventRepository extends JpaRepository<CashEvent, Long> {

    interface LedgerBalance {
        Long getId();

        BigDecimal getBalanceAfter();
    }

    Page<CashEvent> findByPumpIdOrderByEventDateDescCreatedAtDesc(Long pumpId, Pageable pageable);

    boolean existsByPumpIdAndEventTypeAndReversedAtIsNull(Long pumpId, CashEventType eventType);

    /** Filter by event type — used when the event-type filter is active. */
    Page<CashEvent> findByPumpIdAndEventTypeOrderByEventDateDescCreatedAtDesc(
            Long pumpId, CashEventType eventType, Pageable pageable);

    Page<CashEvent> findByPumpIdAndEventDateBetweenOrderByEventDateDescCreatedAtDesc(
            Long pumpId, LocalDate from, LocalDate to, Pageable pageable);

    Page<CashEvent> findByPumpIdAndEventTypeAndEventDateBetweenOrderByEventDateDescCreatedAtDesc(
            Long pumpId, CashEventType eventType, LocalDate from, LocalDate to, Pageable pageable);

    /**
     * Computes the current cash drawer balance in a single DB aggregate query.
     * CASH_OUT and BANK_DEPOSIT subtract; OPENING_BALANCE, CASH_IN and CASH_DROP add.
     * Returns 0 if no relevant events exist.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN e.eventType IN ('CASH_OUT', 'BANK_DEPOSIT') THEN -e.amount ELSE e.amount END
            ), 0)
            FROM CashEvent e
            WHERE e.pumpId = :pumpId
              AND e.eventType IN ('OPENING_BALANCE', 'CASH_IN', 'CASH_OUT', 'CASH_DROP', 'BANK_DEPOSIT')
            """)
    BigDecimal computeBalance(@Param("pumpId") Long pumpId);

    /** The safe's balance once every movement dated up to and including that business day is in. */
    @Query("""
            SELECT COALESCE(SUM(
                CASE WHEN e.eventType IN ('CASH_OUT', 'BANK_DEPOSIT') THEN -e.amount ELSE e.amount END
            ), 0)
            FROM CashEvent e
            WHERE e.pumpId = :pumpId
              AND e.eventType IN ('OPENING_BALANCE', 'CASH_IN', 'CASH_OUT', 'CASH_DROP', 'BANK_DEPOSIT')
              AND e.eventDate <= :businessDate
            """)
    BigDecimal computeBalanceAtEndOf(@Param("pumpId") Long pumpId, @Param("businessDate") LocalDate businessDate);

    /**
     * Ids of the safe's money movements, newest first, each with the balance right after it. The running balance is
     * taken over every movement of the pump before the date filter, so the newest row matches computeBalance.
     */
    @Query(value = """
            SELECT l.id AS id, l.balance_after AS balanceAfter
            FROM (
                SELECT e.id, e.event_date, e.created_at,
                       SUM(CASE WHEN e.event_type IN ('CASH_OUT', 'BANK_DEPOSIT') THEN -e.amount ELSE e.amount END)
                           OVER (ORDER BY e.event_date, e.created_at, e.id) AS balance_after
                FROM cash_events e
                WHERE e.pump_id = :pumpId
                  AND e.event_type IN ('OPENING_BALANCE', 'CASH_IN', 'CASH_OUT', 'CASH_DROP', 'BANK_DEPOSIT')
            ) l
            WHERE l.event_date BETWEEN :from AND :to
            ORDER BY l.event_date DESC, l.created_at DESC, l.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM cash_events e
            WHERE e.pump_id = :pumpId
              AND e.event_type IN ('OPENING_BALANCE', 'CASH_IN', 'CASH_OUT', 'CASH_DROP', 'BANK_DEPOSIT')
              AND e.event_date BETWEEN :from AND :to
            """,
            nativeQuery = true)
    Page<LedgerBalance> findLedgerBalances(@Param("pumpId") Long pumpId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                               Pageable pageable);
}
