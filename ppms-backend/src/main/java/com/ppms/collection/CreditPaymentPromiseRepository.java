package com.ppms.collection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CreditPaymentPromiseRepository extends JpaRepository<CreditPaymentPromise, Long> {

    List<CreditPaymentPromise> findByPumpIdAndStatus(Long pumpId, PromiseStatus status);

    Optional<CreditPaymentPromise> findByClientIdAndStatus(Long clientId, PromiseStatus status);

    List<CreditPaymentPromise> findTop20ByClientIdOrderByRecordedAtDesc(Long clientId);

    List<CreditPaymentPromise> findTop20ByPumpIdAndStatusAndPromisedDateGreaterThanEqualOrderByPromisedDateDesc(
            Long pumpId, PromiseStatus status, LocalDate promisedDate);
}
