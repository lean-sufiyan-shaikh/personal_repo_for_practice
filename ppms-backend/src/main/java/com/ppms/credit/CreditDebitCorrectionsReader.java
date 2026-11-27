package com.ppms.credit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Loads {@link CreditDebitCorrections} for any number of clients in two queries. */
@Component
@RequiredArgsConstructor
public class CreditDebitCorrectionsReader {

    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditDisputeRepository disputeRepository;

    public CreditDebitCorrections of(Collection<Long> clientIds) {
        if (clientIds.isEmpty()) {
            return new CreditDebitCorrections(List.of(), Map.of(), Set.of());
        }
        List<CreditAdjustment> adjustments = adjustmentRepository.findByClientIdIn(clientIds);
        Map<String, BigDecimal> creditedByDebit = adjustments.stream()
                .filter(CreditAdjustment::creditsDebit)
                .collect(Collectors.toMap(adjustment -> CreditDebitCorrections.keyOf(adjustment.getDebitType(),
                        adjustment.getDebitId()), CreditAdjustment::getAmount, BigDecimal::add));
        Set<String> disputed = disputeRepository.findByClientIdInAndStatus(clientIds, CreditDisputeStatus.OPEN).stream()
                .map(dispute -> CreditDebitCorrections.keyOf(dispute.getDebitType(), dispute.getDebitId()))
                .collect(Collectors.toSet());
        return new CreditDebitCorrections(adjustments, creditedByDebit, disputed);
    }
}
