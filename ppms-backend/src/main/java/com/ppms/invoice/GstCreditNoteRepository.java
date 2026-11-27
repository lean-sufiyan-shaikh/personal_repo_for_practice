package com.ppms.invoice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstCreditNoteRepository extends JpaRepository<GstCreditNote, Long> {

    Optional<GstCreditNote> findByIdAndPumpId(Long id, Long pumpId);

    List<GstCreditNote> findByPumpIdOrderByIssuedOnDescIdDesc(Long pumpId);

    List<GstCreditNote> findByPumpIdAndClientIdOrderByIssuedOnDescIdDesc(Long pumpId, Long clientId);

    boolean existsByInvoiceId(Long invoiceId);
}
