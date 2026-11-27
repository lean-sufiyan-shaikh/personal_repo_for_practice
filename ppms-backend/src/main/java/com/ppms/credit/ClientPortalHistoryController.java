package com.ppms.credit;

import com.ppms.auth.ClientIpResolver;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a client can read on the portal within the session their phone and PIN opened (see
 * {@link CreditPortalSessionService}): the last 90 days of their own transactions — a main account's whole group, a
 * sub-account's own — and the statement of this month or last month. Read-only, and limited like the balance lookup.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class ClientPortalHistoryController {

    public static final String SESSION_HEADER = "X-Portal-Session";
    static final int HISTORY_DAYS = 90;
    private static final Set<String> DESCRIBED_TYPES = Set.of("SALE", "COUNTER_SALE");

    private final CreditPortalSessionService sessionService;
    private final CreditBalanceLookupLimiter lookupLimiter;
    private final ClientIpResolver clientIpResolver;
    private final CreditClientRepository clientRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final CreditLedgerQueryService ledgerQueryService;
    private final CreditStatementRenderer statementRenderer;
    private final BusinessClock businessClock;

    /** @param reference what was sold; null for rows whose description is for the pump's staff only */
    public record PortalTransaction(LocalDate date, String type, String accountName, String detail, String reference,
                                    BigDecimal amount, boolean countedInBalance, boolean disputed) {}

    @GetMapping("/credit-history")
    @Transactional(readOnly = true)
    public List<PortalTransaction> getHistory(@RequestHeader(value = SESSION_HEADER, required = false) String token,
                                              HttpServletRequest request) {
        lookupLimiter.checkLookupAllowed(clientIpResolver.resolve(request));
        CreditClient client = sessionClient(token);
        LocalDate since = businessClock.today().minusDays(HISTORY_DAYS);
        return ledgerQueryService.getAllTransactions(client, client.getParentClientId() == null).stream()
                .filter(row -> !row.getBusinessDate().isBefore(since))
                .map(row -> new PortalTransaction(row.getBusinessDate(), row.getType(), row.getClientName(),
                        row.getDetail(), DESCRIBED_TYPES.contains(row.getType()) ? row.getReference() : null,
                        row.getAmount(), row.isCountedInBalance(), row.getOpenDisputeId() != null))
                .toList();
    }

    /** @param month CURRENT (from the 1st to today) or PREVIOUS (the whole of last month) */
    @GetMapping(value = "/credit-statement", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public ResponseEntity<String> getStatement(@RequestHeader(value = SESSION_HEADER, required = false) String token,
                                               @RequestParam String month, HttpServletRequest request) {
        lookupLimiter.checkLookupAllowed(clientIpResolver.resolve(request));
        CreditClient client = sessionClient(token);
        sessionService.checkStatementDownloadAllowed(token);
        YearMonth thisMonth = YearMonth.from(businessClock.today());
        CreditStatement statement;
        LocalDate from;
        LocalDate to;
        switch (month) {
            case "CURRENT" -> {
                from = thisMonth.atDay(1);
                to = businessClock.today();
                statement = ledgerQueryService.getStatement(client, from, to);
            }
            case "PREVIOUS" -> {
                from = thisMonth.minusMonths(1).atDay(1);
                to = thisMonth.minusMonths(1).atEndOfMonth();
                statement = ledgerQueryService.getPeriodStatement(client, from, to);
            }
            default -> throw new BusinessException(ErrorCodes.PORTAL_STATEMENT_MONTH_NOT_ALLOWED, Map.of("month", month),
                    "Only this month's or last month's statement can be downloaded.");
        }
        PumpLocation pump = pumpLocationRepository.findById(client.getPumpId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", CreditLedgerController.STATEMENT_CONTENT_SECURITY_POLICY)
                .body(statementRenderer.render(pump, client, statement, from, to, null));
    }

    /** The session's client, while it is still an active client of an open pump. */
    private CreditClient sessionClient(String token) {
        CreditPortalSessionService.PortalSession session = sessionService.require(token);
        boolean pumpOpen = pumpLocationRepository.findById(session.pumpId()).map(PumpLocation::isEnabled).orElse(false);
        return clientRepository.findById(session.clientId())
                .filter(client -> pumpOpen && client.isActive())
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCodes.CREDIT_ACCOUNT_NOT_MATCHED, Map.of(),
                        "No credit account matches these details."));
    }
}
