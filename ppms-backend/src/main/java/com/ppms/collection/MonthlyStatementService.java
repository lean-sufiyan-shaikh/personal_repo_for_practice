package com.ppms.collection;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates each account's statement for a month and serves the stored copies. A parent account's statement covers
 * its sub-accounts, so only accounts without a parent get one. An inactive account gets one only while it still owes
 * or is owed money at the end of the month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyStatementService {

    private final MonthlyStatementWriter statementWriter;
    private final CreditMonthlyStatementRepository statementRepository;
    private final CreditClientRepository clientRepository;
    private final PumpLocationRepository pumpLocationRepository;

    /** @param failedClientIds accounts whose statement failed and was rolled back; the next run tries them again */
    public record GenerationResult(int written, int skipped, List<Long> failedClientIds) {}

    /** Running it again for the same month writes only the statements still missing. */
    public GenerationResult generateForPump(Long pumpId, YearMonth month) {
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));
        List<CreditClient> accounts = clientRepository.findByPumpIdAndParentClientIdIsNullOrderByNameAsc(pumpId);
        int written = 0;
        int skipped = 0;
        List<Long> failedClientIds = new ArrayList<>();
        for (CreditClient account : accounts) {
            try {
                if (statementWriter.writeInNewTransaction(pump, account.getId(), month)) {
                    written++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.error("Monthly statement failed for pump={} client={} month={}: {}",
                        pumpId, account.getId(), month, e.getMessage(), e);
                failedClientIds.add(account.getId());
            }
        }
        return new GenerationResult(written, skipped, failedClientIds);
    }

    @Transactional(readOnly = true)
    public List<MonthlyStatementResponse> statementsOfMonth(Long pumpId, YearMonth month) {
        return statementRepository.findListOfPumpMonth(pumpId, month.atDay(1));
    }

    @Transactional(readOnly = true)
    public List<MonthlyStatementResponse> statementsOfClient(Long pumpId, Long clientId) {
        return statementRepository.findListOfClient(pumpId, clientId);
    }

    @Transactional(readOnly = true)
    public String storedPage(Long pumpId, Long statementId) {
        return statementRepository.findByIdAndPumpId(statementId, pumpId)
                .map(CreditMonthlyStatement::getHtml)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_STATEMENT,
                        "Credit statement not found: " + statementId));
    }
}
