package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Disputed debits. Raising a dispute takes the debit out of the overdue days, hold, reminders and interest (the
 * interest already charged on it is recalculated); resolving it ACCEPTED puts it back as of its own date, interest
 * included, and CREDITED settles it with a credit note dated today that counts from the debit's own date.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditDisputeService {

    private final CreditDisputeRepository disputeRepository;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditClientRepository clientRepository;
    private final CreditDebitLookup debitLookup;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditInterestService interestService;
    private final CreditNoteService creditNoteService;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public CreditDispute raise(Long pumpId, RaiseDisputeRequest request, User raisedBy) {
        String reason = CreditDeskRules.requiredReason(request.reason());
        CreditDebitLookup.Debit debit = debitLookup.require(pumpId, request.debitType(), request.debitId());
        CreditClient client = requireClient(debit.clientId());
        accountPolicyService.lockLimitHolder(client);
        if (disputeRepository.existsByDebitTypeAndDebitIdAndStatus(debit.type(), debit.id(), CreditDisputeStatus.OPEN)) {
            throw new BusinessException(ErrorCodes.CREDIT_DISPUTE_ALREADY_OPEN, Map.of(),
                    "This debit is already disputed.");
        }
        BigDecimal remaining = debit.amount().subtract(adjustmentRepository.sumCreditedOnDebit(debit.type(), debit.id()));
        if (remaining.signum() <= 0) {
            throw new BusinessException(ErrorCodes.CREDIT_DISPUTE_NOTHING_LEFT, Map.of(),
                    "This debit has been credited in full, so there is nothing left to dispute.");
        }
        CreditDispute dispute = disputeRepository.save(CreditDispute.builder()
                .pumpId(pumpId)
                .clientId(debit.clientId())
                .debitType(debit.type())
                .debitId(debit.id())
                .debitDate(debit.date())
                .amount(CreditDeskRules.money(remaining))
                .reason(reason)
                .raisedBy(raisedBy.getId())
                .build());
        interestService.recalculateChargedInterestFrom(debit.clientId(), debit.date(),
                "Recalculated after " + debit.type() + " #" + debit.id() + " was disputed", raisedBy);
        auditService.log(pumpId, AuditAction.CREDIT_DISPUTE_RAISED, "CreditDispute", dispute.getId().toString(),
                "Disputed " + debit.type() + " #" + debit.id() + " of ₹" + dispute.getAmount() + " for clientId="
                        + debit.clientId() + ", reason=" + reason, raisedBy);
        log.info("Credit dispute raised: pump={} client={} debit={}#{} amount={} by={}", pumpId, debit.clientId(),
                debit.type(), debit.id(), dispute.getAmount(), raisedBy.getId());
        return dispute;
    }

    @Transactional
    public CreditDispute resolve(Long pumpId, Long disputeId, ResolveDisputeRequest request, User resolvedBy) {
        String note = CreditDeskRules.requiredReason(request.note());
        CreditDispute dispute = disputeRepository.findByIdAndPumpIdForUpdate(disputeId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_DISPUTE, "Dispute not found"));
        CreditClient client = requireClient(dispute.getClientId());
        accountPolicyService.lockLimitHolder(client);
        if (!dispute.isOpen()) {
            throw new BusinessException(ErrorCodes.CREDIT_DISPUTE_NOT_OPEN, Map.of("statusCode", dispute.getStatus().name()),
                    "This dispute is already " + dispute.getStatus() + ".");
        }
        if (request.resolution() == CreditDisputeStatus.OPEN) {
            throw new BusinessException(ErrorCodes.CREDIT_DISPUTE_RESOLUTION_INVALID, Map.of(),
                    "Resolve a dispute as accepted or credited.");
        }
        dispute.setStatus(request.resolution());
        dispute.setResolvedBy(resolvedBy.getId());
        dispute.setResolvedAt(businessClock.now());
        dispute.setResolutionNote(note);
        disputeRepository.saveAndFlush(dispute);

        if (request.resolution() == CreditDisputeStatus.CREDITED) {
            BigDecimal amount = request.creditAmount() != null ? request.creditAmount() : dispute.getAmount();
            creditNoteService.issue(client, debitLookup.require(pumpId, dispute.getDebitType(), dispute.getDebitId()),
                    new IssueCreditNoteRequest(amount, note, dispute.getDebitType(), dispute.getDebitId(), null),
                    dispute.getId(), resolvedBy);
        } else {
            interestService.recalculateChargedInterestFrom(dispute.getClientId(), dispute.getDebitDate(),
                    "Recalculated after the dispute on " + dispute.getDebitType() + " #" + dispute.getDebitId()
                            + " was accepted", resolvedBy);
        }
        interestService.chargeUncoveredDaysThroughLastRun(dispute.getClientId(), resolvedBy);
        auditService.log(pumpId, AuditAction.CREDIT_DISPUTE_RESOLVED, "CreditDispute", dispute.getId().toString(),
                "Dispute on " + dispute.getDebitType() + " #" + dispute.getDebitId() + " resolved " + dispute.getStatus()
                        + ", note=" + note, resolvedBy);
        log.info("Credit dispute resolved: pump={} dispute={} resolution={} by={}", pumpId, disputeId,
                dispute.getStatus(), resolvedBy.getId());
        return dispute;
    }

    @Transactional(readOnly = true)
    public List<CreditDispute> disputesOfAccount(CreditClient client) {
        List<Long> accountIds = new ArrayList<>(List.of(client.getId()));
        if (client.getParentClientId() == null) {
            clientRepository.findByParentClientId(client.getId()).forEach(child -> accountIds.add(child.getId()));
        }
        return disputeRepository.findByClientIdIn(accountIds);
    }

    private CreditClient requireClient(Long clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT, "Credit client not found"));
    }
}
