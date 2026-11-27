package com.ppms.supplier;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface FuelSupplierPaymentRepository extends JpaRepository<FuelSupplierPayment, Long> {

    /** Returns paginated payments for a pump, newest first. */
    Page<FuelSupplierPayment> findByPumpIdOrderByPaymentDateDescCreatedAtDesc(Long pumpId, Pageable pageable);

    /** Returns paginated payments to a specific supplier, newest first. */
    Page<FuelSupplierPayment> findByPumpIdAndSupplierIdOrderByPaymentDateDesc(Long pumpId, Long supplierId, Pageable pageable);

    @Query("""
            SELECT COUNT(p) > 0 FROM FuelSupplierPayment p
            WHERE p.supplierId = :supplierId AND p.voidStatus = 'ACTIVE'
              AND LOWER(TRIM(p.referenceNo)) = LOWER(:referenceNo)
            """)
    boolean existsActiveWithReference(@Param("supplierId") Long supplierId, @Param("referenceNo") String referenceNo);

    Optional<FuelSupplierPayment> findByIdAndPumpId(Long id, Long pumpId);

    List<FuelSupplierPayment> findByPumpIdAndVoidStatus(Long pumpId, String voidStatus);

    List<FuelSupplierPayment> findByPumpIdAndSupplierIdAndVoidStatus(Long pumpId, Long supplierId, String voidStatus);

    /** Total amount paid to a supplier by a pump, voided payments left out — used for outstanding dues. */
    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM FuelSupplierPayment p
            WHERE p.pumpId = :pumpId AND p.supplierId = :supplierId AND p.voidStatus = 'ACTIVE'
            """)
    BigDecimal sumAmountByPumpAndSupplier(@Param("pumpId") Long pumpId, @Param("supplierId") Long supplierId);
}
