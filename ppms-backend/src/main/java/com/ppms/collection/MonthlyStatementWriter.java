package com.ppms.collection;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditLedgerQueryService;
import com.ppms.credit.CreditStatement;
import com.ppms.credit.CreditStatementRenderer;
import com.ppms.pump.PumpLocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;

/** Writes one client's statement for one month in its own transaction, so one failure does not undo the others. */
@Service
@RequiredArgsConstructor
public class MonthlyStatementWriter {

    private final CreditMonthlyStatementRepository statementRepository;
    private final CreditClientRepository clientRepository;
    private final CreditLedgerQueryService ledgerQueryService;
    private final CreditStatementRenderer statementRenderer;

    /**
     * False when the month's statement already exists, or when the client owes nothing at the end of the month and
     * either had nothing in the month or is inactive. The pay link asks for the balance at the end of the month.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean writeInNewTransaction(PumpLocation pump, Long clientId, YearMonth month) {
        LocalDate periodFrom = month.atDay(1);
        LocalDate periodTo = month.atEndOfMonth();
        if (statementRepository.existsByClientIdAndPeriodFrom(clientId, periodFrom)) {
            return false;
        }
        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                        "Credit client not found: " + clientId));
        CreditStatement statement = ledgerQueryService.getPeriodStatement(client, periodFrom, periodTo);
        boolean closesAtZero = statement.outstanding().signum() == 0;
        if (closesAtZero && (statement.transactions().isEmpty() || !client.isActive())) {
            return false;
        }
        String html = statementRenderer.render(pump, client, statement, periodFrom, periodTo,
                UpiPaymentLink.forPump(pump, statement.outstanding(), client.getName()));
        statementRepository.save(CreditMonthlyStatement.builder()
                .pumpId(pump.getId())
                .clientId(clientId)
                .periodFrom(periodFrom)
                .periodTo(periodTo)
                .closingBalance(statement.outstanding())
                .transactionCount(statement.transactions().size())
                .html(html)
                .build());
        return true;
    }
}
