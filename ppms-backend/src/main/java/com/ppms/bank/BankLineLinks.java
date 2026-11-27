package com.ppms.bank;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Keeps statement-line links consistent when a linked record is withdrawn elsewhere. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankLineLinks {

    private final BankStatementLineRepository lineRepository;

    /**
     * A settlement confirmed on a bank line cannot be voided until the line is unmatched; a suggestion pointing
     * at it is simply dropped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseSettlementBeforeVoid(Long settlementId) {
        lineRepository.findByMatchedSettlementId(settlementId)
                .filter(line -> line.getMatchStatus() == BankLineMatchStatus.MATCHED)
                .ifPresent(line -> {
                    throw new BusinessException(ErrorCodes.SETTLEMENT_MATCHED_TO_BANK_LINE,
                            Map.of("lineId", line.getId(), "txnDate", line.getTxnDate().toString()),
                            "This settlement is matched to the bank statement line of " + line.getTxnDate()
                            + ". Unmatch that line in Bank reconciliation before voiding the settlement.");
                });
        int cleared = lineRepository.unlinkSettlement(settlementId, BankLineMatchStatus.SUGGESTED, BankLineMatchStatus.UNMATCHED);
        if (cleared > 0) {
            log.info("Dropped {} bank line suggestion(s) for voided settlement {}", cleared, settlementId);
        }
    }
}
