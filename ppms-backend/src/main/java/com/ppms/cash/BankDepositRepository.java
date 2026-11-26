package com.ppms.cash;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

public interface BankDepositRepository extends JpaRepository<BankDeposit, Long> {

    interface DepositTotal {
        long getDepositCount();

        BigDecimal getAmount();
    }

    Optional<BankDeposit> findByIdAndPumpId(Long id, Long pumpId);

    boolean existsByPumpIdAndSlipReferenceIgnoreCase(Long pumpId, String slipReference);

    @Query("""
            SELECT d FROM BankDeposit d
            WHERE d.pumpId = :pumpId AND d.depositDate BETWEEN :from AND :to
              AND (:bankAccount IS NULL OR LOWER(d.bankAccount) LIKE LOWER(CONCAT('%', CAST(:bankAccount AS string), '%')))
            ORDER BY d.depositDate DESC, d.createdAt DESC, d.id DESC
            """)
    Page<BankDeposit> findForList(@Param("pumpId") Long pumpId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                  @Param("bankAccount") String bankAccount, Pageable pageable);

    @Query("""
            SELECT COUNT(d) AS depositCount, COALESCE(SUM(d.amount), 0) AS amount
            FROM BankDeposit d
            WHERE d.pumpId = :pumpId AND d.depositDate BETWEEN :from AND :to
              AND (:bankAccount IS NULL OR LOWER(d.bankAccount) LIKE LOWER(CONCAT('%', CAST(:bankAccount AS string), '%')))
            """)
    DepositTotal totalForList(@Param("pumpId") Long pumpId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                              @Param("bankAccount") String bankAccount);
}
