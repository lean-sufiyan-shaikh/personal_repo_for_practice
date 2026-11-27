package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The end of a credit account: writing off a bad debt, closing the account and reopening it. Owner only.
 * A write-off is booked on the main account; from its date the group bears no interest and is no longer overdue, held
 * or reminded. Closing needs nothing owed, no advance, no deposit held and no payment awaiting approval; it disables
 * the account with its sub-accounts and keeps all their history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditAccountClosureService {

    private final CreditClientAccessService clientAccessService;
    private final CreditClientRepository clientRepository;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditInterestService interestService;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditDepositRepository depositRepository;
    private final CreditPaymentRepository paymentRepository;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;
    private final BalanceSheetStalenessService balanceSheetStalenessService;

    /** @param amount null writes off all the group owes */
    @Transactional
    public CreditAdjustment writeOff(Long pumpId, Long clientId, BigDecimal amount, String reason, User writtenOffBy) {
        CreditClient holder = accountPolicyService.lockLimitHolder(clientAccessService.requireClientForPump(pumpId, clientId));
        String trimmedReason = CreditDeskRules.requiredReason(reason);
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(pumpId, today);
        BigDecimal owed = interestService.computeOutstandingWithChildren(holder.getId()).max(BigDecimal.ZERO);
        BigDecimal writtenOff = CreditDeskRules.money(amount != null ? amount : owed);
        if (writtenOff.signum() <= 0) {
            throw new BusinessException(ErrorCodes.CREDIT_WRITE_OFF_NOTHING_OWED, Map.of("clientName", holder.getName()),
                    "'" + holder.getName() + "' owes nothing, so there is nothing to write off.");
        }
        CreditDeskRules.requireAtMost(writtenOff, owed, ErrorCodes.CREDIT_WRITE_OFF_EXCEEDS_OUTSTANDING,
                "At most what the account owes can be written off");

        if (holder.getWrittenOffOn() == null) {
            holder.setWrittenOffOn(today);
            clientRepository.save(holder);
        }
        CreditAdjustment writeOff = adjustmentRepository.save(CreditAdjustment.builder()
                .pumpId(pumpId)
                .clientId(holder.getId())
                .kind(CreditAdjustmentKind.WRITE_OFF)
                .amount(writtenOff)
                .adjustmentDate(today)
                .reason(trimmedReason)
                .createdBy(writtenOffBy.getId())
                .build());
        interestService.recalculateChargedInterestFrom(holder.getId(), today,
                "Recalculated after a bad-debt write-off on " + today, writtenOffBy);
        balanceSheetStalenessService.markDaySheetsStale(pumpId, List.of(today),
                StaleReasons.creditAccountChanged(holder.getId()));
        auditService.log(pumpId, AuditAction.CREDIT_WRITTEN_OFF, "CreditAdjustment", writeOff.getId().toString(),
                "Bad debt ₹" + writtenOff + " written off for clientId=" + holder.getId() + " (owed ₹"
                        + CreditDeskRules.money(owed) + "), reason=" + trimmedReason, writtenOffBy);
        log.info("Bad debt written off: pump={} client={} amount={} owed={} by={}", pumpId, holder.getId(), writtenOff,
                owed, writtenOffBy.getId());
        return writeOff;
    }

    @Transactional
    public CreditClient close(Long pumpId, Long clientId, String reason, User closedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        accountPolicyService.lockLimitHolder(client);
        String trimmedReason = CreditDeskRules.requiredReason(reason);
        CreditDeskRules.requireOpenAccount(client);
        List<CreditClient> accounts = accountsClosedWith(client);
        requireNothingLeft(client, accounts);
        accounts.forEach(account -> markClosed(account, trimmedReason, closedBy));
        clientRepository.saveAll(accounts);
        auditService.log(pumpId, AuditAction.CREDIT_ACCOUNT_CLOSED, "CreditClient", clientId.toString(),
                "Account '" + client.getName() + "' closed" + (accounts.size() > 1
                        ? " with " + (accounts.size() - 1) + " sub-account(s)" : "") + ", reason=" + trimmedReason, closedBy);
        log.info("Credit account closed: pump={} client={} accounts={} by={}", pumpId, clientId, accounts.size(),
                closedBy.getId());
        return client;
    }

    /** A merged duplicate stays closed; a sub-account reopens only under an open parent. */
    @Transactional
    public CreditClient reopen(Long pumpId, Long clientId, User reopenedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        accountPolicyService.lockLimitHolder(client);
        if (!client.isClosed()) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_NOT_CLOSED, Map.of("clientName", client.getName()),
                    "The account of '" + client.getName() + "' is not closed.");
        }
        if (client.getMergedIntoClientId() != null) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_MERGED, Map.of("clientName", client.getName()),
                    "'" + client.getName() + "' was merged into another client, so it cannot be reopened.");
        }
        if (client.getParentClientId() != null) {
            clientRepository.findById(client.getParentClientId()).ifPresent(CreditDeskRules::requireOpenAccount);
        }
        List<CreditClient> accounts = accountsClosedWith(client).stream()
                .filter(account -> account.isClosed() && account.getMergedIntoClientId() == null)
                .toList();
        accounts.forEach(account -> {
            account.setClosedAt(null);
            account.setClosedBy(null);
            account.setCloseReason(null);
            account.setWrittenOffOn(null);
            account.setActive(true);
        });
        clientRepository.saveAll(accounts);
        auditService.log(pumpId, AuditAction.CREDIT_ACCOUNT_REOPENED, "CreditClient", clientId.toString(),
                "Account '" + client.getName() + "' reopened", reopenedBy);
        log.info("Credit account reopened: pump={} client={} by={}", pumpId, clientId, reopenedBy.getId());
        return client;
    }

    private List<CreditClient> accountsClosedWith(CreditClient client) {
        List<CreditClient> accounts = new ArrayList<>(List.of(client));
        if (client.getParentClientId() == null) {
            accounts.addAll(clientRepository.findByParentClientId(client.getId()));
        }
        return accounts;
    }

    private void requireNothingLeft(CreditClient client, List<CreditClient> accounts) {
        List<Long> accountIds = accounts.stream().map(CreditClient::getId).toList();
        BigDecimal balance = accounts.size() > 1 || client.getParentClientId() == null
                ? interestService.computeOutstandingWithChildren(client.getId())
                : interestService.computeOutstanding(client.getId());
        if (balance.signum() != 0) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_CLOSE_HAS_BALANCE,
                    Map.of("clientName", client.getName(), "balance", CreditDeskRules.money(balance).toPlainString()),
                    "'" + client.getName() + "' has a balance of ₹" + CreditDeskRules.money(balance).toPlainString()
                            + ". Settle it, write it off or refund the advance before closing.");
        }
        BigDecimal depositHeld = accountIds.stream().map(depositRepository::heldByClientId)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (depositHeld.signum() != 0) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_CLOSE_HAS_DEPOSIT,
                    Map.of("clientName", client.getName(), "deposit", CreditDeskRules.money(depositHeld).toPlainString()),
                    "'" + client.getName() + "' still has a security deposit of ₹"
                            + CreditDeskRules.money(depositHeld).toPlainString() + ". Refund or apply it before closing.");
        }
        if (paymentRepository.sumPendingAmountByClientIds(accountIds).signum() != 0) {
            throw new BusinessException(ErrorCodes.CREDIT_ACCOUNT_CLOSE_HAS_PENDING_PAYMENTS,
                    Map.of("clientName", client.getName()),
                    "'" + client.getName() + "' has payments waiting for approval. Approve or reject them first.");
        }
    }

    private void markClosed(CreditClient account, String reason, User closedBy) {
        account.setClosedAt(businessClock.now());
        account.setClosedBy(closedBy.getId());
        account.setCloseReason(reason);
        account.setActive(false);
    }
}
