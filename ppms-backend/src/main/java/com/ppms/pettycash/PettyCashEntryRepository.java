package com.ppms.pettycash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface PettyCashEntryRepository extends JpaRepository<PettyCashEntry, Long> {

    interface LedgerBalance {
        Long getId();

        BigDecimal getBalanceAfter();
    }

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM PettyCashEntry e WHERE e.pumpId = :pumpId")
    BigDecimal computeBalance(@Param("pumpId") Long pumpId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM PettyCashEntry e WHERE e.pumpId = :pumpId AND e.entryDate <= :date")
    BigDecimal computeBalanceAtEndOf(@Param("pumpId") Long pumpId, @Param("date") LocalDate date);

    boolean existsByPumpId(Long pumpId);

    Optional<PettyCashEntry> findByExpenseIdAndEntryType(Long expenseId, PettyCashEntryType entryType);

    /** Movements newest first, each with the box's balance right after it, taken over every movement of the pump. */
    @Query(value = """
            SELECT l.id AS id, l.balance_after AS balanceAfter
            FROM (
                SELECT e.id, e.entry_date, e.created_at,
                       SUM(e.amount) OVER (ORDER BY e.entry_date, e.created_at, e.id) AS balance_after
                FROM petty_cash_entries e
                WHERE e.pump_id = :pumpId
            ) l
            WHERE l.entry_date BETWEEN :from AND :to
            ORDER BY l.entry_date DESC, l.created_at DESC, l.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM petty_cash_entries e
            WHERE e.pump_id = :pumpId AND e.entry_date BETWEEN :from AND :to
            """,
            nativeQuery = true)
    Page<LedgerBalance> findLedgerBalances(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                           @Param("to") LocalDate to, Pageable pageable);
}
