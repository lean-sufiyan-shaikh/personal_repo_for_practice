package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.invoice.GstCreditNote;
import com.ppms.invoice.GstCreditNoteService;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Issues credit notes. A note naming a sale or charge clears that debit before the account's oldest bills and can
 * be at most what is left of it; a note naming nothing clears the oldest bills and can be at most the balance. The
 * interest already charged from the note's date is recalculated, and a note on an invoiced sale also gets a GST credit
 * note. Dated today, so today must be open.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    private final CreditClientAccessService clientAccessService;
    private final CreditAccountPolicyService accountPolicyService;
    private final CreditAdjustmentRepository adjustmentRepository;
    private final CreditDisputeRepository disputeRepository;
    private final CreditDebitLookup debitLookup;
    private final CreditInterestService interestService;
    private final GstCreditNoteService gstCreditNoteService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public record IssuedCreditNote(CreditAdjustment adjustment, Optional<GstCreditNote> gstCreditNote) {}

    @Transactional
    public IssuedCreditNote issue(Long pumpId, Long clientId, IssueCreditNoteRequest request, User issuedBy) {
        CreditClient client = clientAccessService.requireClientForPump(pumpId, clientId);
        accountPolicyService.lockLimitHolder(client);
        if ((request.debitType() == null) != (request.debitId() == null)) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_DEBIT_INCOMPLETE, Map.of(),
                    "Name both the kind and the id of the debit being credited, or neither.");
        }
        CreditDebitLookup.Debit debit = request.debitType() == null ? null
                : debitLookup.require(pumpId, request.debitType(), request.debitId());
        if (debit != null && disputeRepository.existsByDebitTypeAndDebitIdAndStatus(debit.type(), debit.id(),
                CreditDisputeStatus.OPEN)) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_DEBIT_DISPUTED, Map.of(),
                    "This debit is disputed. Resolve the dispute as credited instead.");
        }
        return issue(client, debit, request, null, issuedBy);
    }

    /** Issues the note; a dispute resolved as credited passes its own id and has already locked the account. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedCreditNote issue(CreditClient client, CreditDebitLookup.Debit debit, IssueCreditNoteRequest request,
                                  Long disputeId, User issuedBy) {
        CreditDeskRules.requireOpenAccount(client);
        String reason = CreditDeskRules.requiredReason(request.reason());
        BigDecimal amount = CreditDeskRules.money(request.amount());
        LocalDate today = businessClock.today();
        closedDayGuard.requireOpen(client.getPumpId(), today);
        if (debit != null) {
            requireCreditableDebit(client, debit, amount, request.returnedQuantity());
        } else {
            BigDecimal outstanding = interestService.computeOutstandingWithChildren(accountPolicyService.limitHolderId(client));
            CreditDeskRules.requireAtMost(amount, outstanding.max(BigDecimal.ZERO), ErrorCodes.CREDIT_NOTE_EXCEEDS_OUTSTANDING,
                    "A credit note naming no sale can be at most what the account owes");
            if (request.returnedQuantity() != null) {
                throw new BusinessException(ErrorCodes.CREDIT_NOTE_QUANTITY_WITHOUT_SALE, Map.of(),
                        "A returned quantity needs the sale it was returned from.");
            }
        }

        CreditAdjustment note = adjustmentRepository.save(CreditAdjustment.builder()
                .pumpId(client.getPumpId())
                .clientId(debit != null ? debit.clientId() : client.getId())
                .kind(CreditAdjustmentKind.CREDIT_NOTE)
                .amount(amount)
                .adjustmentDate(today)
                .reason(reason)
                .debitType(debit != null ? debit.type() : null)
                .debitId(debit != null ? debit.id() : null)
                .debitDate(debit != null ? debit.date() : null)
                .returnedQuantity(request.returnedQuantity())
                .disputeId(disputeId)
                .createdBy(issuedBy.getId())
                .build());
        interestService.recalculateChargedInterestFrom(note.getClientId(), note.principalDate(),
                "Recalculated after a credit note dated " + today, issuedBy);
        Optional<GstCreditNote> gstCreditNote = debit != null
                ? gstCreditNoteService.issueIfInvoiced(note, issuedBy) : Optional.empty();

        auditService.log(client.getPumpId(), AuditAction.CREDIT_NOTE_ISSUED, "CreditAdjustment", note.getId().toString(),
                "Credit note ₹" + amount + " for clientId=" + note.getClientId()
                        + (debit != null ? " against " + debit.type() + " #" + debit.id() : " against the oldest bills")
                        + gstCreditNote.map(gst -> ", GST credit note " + gst.getCreditNoteNumber()).orElse("")
                        + ", reason=" + reason, issuedBy);
        log.info("Credit note issued: pump={} client={} amount={} debit={} gstCreditNote={} by={}", client.getPumpId(),
                note.getClientId(), amount, debit != null ? debit.type() + "#" + debit.id() : "none",
                gstCreditNote.map(GstCreditNote::getCreditNoteNumber).orElse("none"), issuedBy.getId());
        return new IssuedCreditNote(note, gstCreditNote);
    }

    private void requireCreditableDebit(CreditClient client, CreditDebitLookup.Debit debit, BigDecimal amount,
                                        BigDecimal returnedQuantity) {
        if (!debit.clientId().equals(client.getId())) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_DEBIT_OTHER_CLIENT, Map.of(),
                    "This debit belongs to another account. Issue the credit note on that account.");
        }
        BigDecimal remaining = debit.amount().subtract(adjustmentRepository.sumCreditedOnDebit(debit.type(), debit.id()));
        CreditDeskRules.requireAtMost(amount, remaining.max(BigDecimal.ZERO), ErrorCodes.CREDIT_NOTE_EXCEEDS_DEBIT,
                "A credit note can be at most what is left of the debit after earlier credit notes");
        if (returnedQuantity == null) {
            return;
        }
        if (debit.quantity() == null) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_QUANTITY_WITHOUT_SALE, Map.of(),
                    "A returned quantity needs the sale it was returned from.");
        }
        if (returnedQuantity.compareTo(debit.quantity()) > 0) {
            throw new BusinessException(ErrorCodes.CREDIT_NOTE_QUANTITY_EXCEEDS_SALE,
                    Map.of("quantity", returnedQuantity.stripTrailingZeros().toPlainString(),
                            "sold", debit.quantity().stripTrailingZeros().toPlainString()),
                    "Returned quantity " + returnedQuantity.stripTrailingZeros().toPlainString()
                            + " is more than the " + debit.quantity().stripTrailingZeros().toPlainString() + " sold.");
        }
    }
}
