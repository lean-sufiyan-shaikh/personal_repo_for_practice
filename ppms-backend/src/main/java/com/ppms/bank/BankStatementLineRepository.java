package com.ppms.bank;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BankStatementLineRepository extends JpaRepository<BankStatementLine, Long> {

    /** Returns all lines for an import, ordered by transaction date. */
    List<BankStatementLine> findByImportIdOrderByTxnDateAscIdAsc(Long importId);

    /** Lines of an import in the given states, oldest first. */
    List<BankStatementLine> findByImportIdAndMatchStatusInOrderByTxnDateAscIdAsc(Long importId,
                                                                                  Collection<BankLineMatchStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM BankStatementLine l WHERE l.id = :id")
    Optional<BankStatementLine> findByIdForUpdate(@Param("id") Long id);

    /** Count of matched lines for an import — used to update the matched_lines counter. */
    @Query("SELECT COUNT(l) FROM BankStatementLine l WHERE l.importId = :importId AND l.matchStatus = 'MATCHED'")
    int countMatchedByImportId(@Param("importId") Long importId);

    /** Per import and status: number of lines, credit total and debit total. */
    @Query("""
            SELECT l.importId, l.matchStatus, COUNT(l), COALESCE(SUM(l.creditAmount), 0), COALESCE(SUM(l.debitAmount), 0)
            FROM BankStatementLine l
            WHERE l.importId IN :importIds
            GROUP BY l.importId, l.matchStatus
            """)
    List<Object[]> summariseByImportIds(@Param("importIds") Collection<Long> importIds);

    /** UTR references among {@code utrReferences} that already appear on an earlier statement of the pump. */
    @Query("""
            SELECT DISTINCT l.utrReference FROM BankStatementLine l, BankStatementImport i
            WHERE l.importId = i.id AND i.pumpId = :pumpId AND l.utrReference IN :utrReferences
            """)
    List<String> findImportedUtrReferences(@Param("pumpId") Long pumpId,
                                           @Param("utrReferences") Collection<String> utrReferences);

    /** Line hashes among {@code lineHashes} that already appear on an earlier statement of the pump. */
    @Query("""
            SELECT DISTINCT l.lineHash FROM BankStatementLine l, BankStatementImport i
            WHERE l.importId = i.id AND i.pumpId = :pumpId AND l.lineHash IN :lineHashes
            """)
    List<String> findImportedLineHashes(@Param("pumpId") Long pumpId, @Param("lineHashes") Collection<String> lineHashes);

    Optional<BankStatementLine> findByMatchedSettlementId(Long settlementId);

    Optional<BankStatementLine> findByMatchedDepositId(Long depositId);

    Optional<BankStatementLine> findByMatchedSupplierPaymentId(Long supplierPaymentId);

    Optional<BankStatementLine> findByMatchedPaymentId(Long paymentId);

    /** Moves lines linked to a settlement in {@code fromStatus} to {@code toStatus} without the link. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE BankStatementLine l SET l.matchStatus = :toStatus, l.matchedSettlementId = NULL
            WHERE l.matchedSettlementId = :settlementId AND l.matchStatus = :fromStatus
            """)
    int unlinkSettlement(@Param("settlementId") Long settlementId,
                         @Param("fromStatus") BankLineMatchStatus fromStatus,
                         @Param("toStatus") BankLineMatchStatus toStatus);
}
