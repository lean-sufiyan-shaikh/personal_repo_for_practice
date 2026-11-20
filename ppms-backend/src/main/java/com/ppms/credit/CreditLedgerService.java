package com.ppms.credit;

import com.ppms.common.time.BusinessClock;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.expense.ApprovalPolicy;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.UserRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Service layer for credit ledger business operations.
 *
 * @Transactional lives here — not on the controller — so DB transactions are scoped
 * to service methods only, not to the full HTTP request lifecycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditLedgerService {

    private final CreditClientRepository clientRepository;
    private final CreditPaymentRepository paymentRepository;
    private final CreditInterestChargeRepository interestChargeRepository;
    private final ShiftCreditEntryRepository creditEntryRepository;
    private final ShiftRepository shiftRepository;
    private final CreditEntryReassignmentRepository reassignmentRepository;
    private final CreditInterestService interestService;
    private final ApprovalPolicy approvalPolicy;
    private final UserRepository userRepository;
    private final BusinessClock businessClock;

    /**
     * Records a payment settlement against a client's outstanding balance.
     *
     * Bug 1 fix: for parent accounts the overpayment guard now checks the combined
     * outstanding (parent + all sub-accounts), not just the parent's own balance.
     * Previously a parent with ₹0 own but ₹200k in children would block any payment.
     */
    @Transactional
    public CreditPayment recordPayment(Long pumpId, Long clientId,
                                       RecordPaymentRequest request, User currentUser) {

        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));

        if (!client.getPumpId().equals(pumpId)) {
            throw new BusinessException("Client does not belong to this pump");
        }

        lockPaymentGroup(client);

        // For parent accounts: outstanding includes all children — payments against the parent
        // can settle up to the full group balance, not just the parent's own portion.
        // Payments still awaiting approval are reserved so they cannot together over-pay.
        List<Long> scopeClientIds = paymentScopeClientIds(clientId);
        BigDecimal outstanding = outstandingForPaymentScope(clientId, scopeClientIds);
        BigDecimal pending = paymentRepository.sumPendingAmountByClientIds(scopeClientIds);
        BigDecimal payable = outstanding.subtract(pending).setScale(2, RoundingMode.HALF_UP);

        BigDecimal paymentAmount = request.getAmount().setScale(2, RoundingMode.HALF_UP);
        if (paymentAmount.compareTo(payable) > 0) {
            throw new BusinessException(
                    "Payment amount (" + paymentAmount + ") exceeds the outstanding balance (" + outstanding + ")"
                            + (pending.signum() > 0 ? " less payments awaiting approval (" + pending + ")" : "")
                            + ". You cannot over-pay a credit account.");
        }

        ExpenseApprovalStatus approvalStatus = approvalPolicy.resolveStatus(pumpId, currentUser, paymentAmount);
        if (approvalStatus == ExpenseApprovalStatus.PENDING_APPROVAL) {
            log.info("Credit payment set to PENDING_APPROVAL: pumpId={} amount={}", pumpId, paymentAmount);
        }

        CreditPayment payment = CreditPayment.builder()
                .pumpId(pumpId)
                .clientId(clientId)
                .amount(paymentAmount)
                .paymentMode(request.getPaymentMode())
                .referenceNo(request.getReferenceNo())
                .notes(request.getNotes())
                .paidAt(request.getPaidAt())
                .recordedById(currentUser.getId())
                .paymentApprovalStatus(approvalStatus)
                .build();

        payment = paymentRepository.save(payment);

        log.info("Credit payment recorded: pump={}, client={}, amount={}, mode={}, status={}, by={}",
                pumpId, clientId, payment.getAmount(), payment.getPaymentMode(),
                payment.getPaymentApprovalStatus(), currentUser.getId());

        return payment;
    }

    public CreditClient saveClient(CreditClient client) {
        return clientRepository.save(client);
    }

    /**
     * Permanently removes an interest charge from the ledger.
     * Outstanding balance recalculates automatically on the next fetch since
     * computeOutstanding() sums directly from the DB.
     */
    @Transactional
    public void deleteInterestCharge(Long pumpId, Long clientId, Long chargeId, User currentUser) {

        CreditInterestCharge charge = interestChargeRepository.findById(chargeId)
                .orElseThrow(() -> new ResourceNotFoundException("Interest charge not found"));

        if (!charge.getClientId().equals(clientId)) {
            throw new BusinessException("Interest charge does not belong to this client");
        }

        if (!charge.getPumpId().equals(pumpId)) {
            throw new BusinessException("Interest charge does not belong to this pump");
        }

        interestChargeRepository.deleteById(chargeId);

        log.info("Interest charge deleted: pump={}, client={}, chargeId={}, amount={}, period={}/{}, deletedBy={}",
                pumpId, clientId, chargeId, charge.getAmount(),
                charge.getPeriodFrom(), charge.getPeriodTo(), currentUser.getId());
    }

    /**
     * Moves a credit entry from its current account to a different account within the same group.
     *
     * Bug 6 fix: when the entry has no prior clientId (was never linked), the audit record
     * previously stored fromClientId = toClientId, making it look like a no-op. Now it stores
     * null explicitly so the audit trail accurately reflects "previously unlinked → toClient".
     */
    @Transactional
    public void reassignCreditEntry(Long pumpId, Long entryId,
                                    ReassignCreditEntryRequest request, User currentUser) {

        ShiftCreditEntry entry = creditEntryRepository.findById(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit entry not found"));

        Shift shift = shiftRepository.findById(entry.getShiftId())
                .orElseThrow(() -> new ResourceNotFoundException("Shift not found"));
        if (!shift.getPumpId().equals(pumpId)) {
            throw new BusinessException("Credit entry does not belong to this pump");
        }

        CreditClient toClient = clientRepository.findById(request.getToClientId())
                .orElseThrow(() -> new ResourceNotFoundException("Target credit client not found"));
        if (!toClient.getPumpId().equals(pumpId)) {
            throw new BusinessException("Target client does not belong to this pump");
        }

        if (entry.getClientId() != null && entry.getClientId().equals(request.getToClientId())) {
            throw new BusinessException("Entry is already assigned to this account");
        }

        Long fromClientId = entry.getClientId(); // null when entry was never linked to a client
        validateGroupMove(pumpId, fromClientId, request.getToClientId());

        // Bug 6 fix: only save an audit record when the entry had a previous client.
        // When fromClientId is null the entry was never assigned — this is an initial assignment,
        // not a re-assignment. The old code stored fromClientId = toClientId in this case,
        // which made the audit log look like a no-op (moved from X to X).
        // from_client_id is NOT NULL in the schema, so we cannot store null;
        // skipping the audit record entirely is more accurate than a misleading from==to entry.
        // The action is still fully captured by the log statement below.
        if (fromClientId != null) {
            reassignmentRepository.save(CreditEntryReassignment.builder()
                    .creditEntryId(entryId)
                    .fromClientId(fromClientId)
                    .toClientId(request.getToClientId())
                    .reassignedByUserId(currentUser.getId())
                    .reason(request.getReason().trim())
                    .build());
        } else {
            log.warn("Credit entry {} had no prior client (initial assignment to client={}), " +
                    "skipping audit record to avoid misleading from==to entry", entryId, request.getToClientId());
        }

        entry.setClientId(request.getToClientId());
        creditEntryRepository.save(entry);

        log.info("Credit entry {} moved: pump={}, fromClient={}, toClient={}, by={}",
                entryId, pumpId, fromClientId != null ? fromClientId : "unlinked",
                request.getToClientId(), currentUser.getId());
    }

    @Transactional
    public void reassignCreditPayment(Long pumpId, Long paymentId,
                                      ReassignCreditEntryRequest request, User currentUser) {

        CreditPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit payment not found"));

        if (!payment.getPumpId().equals(pumpId)) {
            throw new BusinessException("Credit payment does not belong to this pump");
        }

        if (payment.getClientId().equals(request.getToClientId())) {
            throw new BusinessException("Payment is already assigned to this account");
        }

        validateGroupMove(pumpId, payment.getClientId(), request.getToClientId());

        Long fromClientId = payment.getClientId();
        payment.setClientId(request.getToClientId());
        paymentRepository.save(payment);

        log.info("Credit payment {} moved: pump={}, fromClient={}, toClient={}, by={}",
                paymentId, pumpId, fromClientId, request.getToClientId(), currentUser.getId());
    }

    @Transactional
    public CreditPayment approvePayment(Long pumpId, Long paymentId,
                                        ExpenseApprovalStatus newStatus, User currentUser) {
        Long clientId = paymentRepository.findClientIdByIdAndPumpId(paymentId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        CreditClient client = clientRepository.findById(clientId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));
        lockPaymentGroup(client);

        CreditPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));

        if (payment.getPaymentApprovalStatus() != ExpenseApprovalStatus.PENDING_APPROVAL) {
            throw new BusinessException(
                    "Payment is already " + payment.getPaymentApprovalStatus() + " — only PENDING_APPROVAL payments can be acted on");
        }
        if (payment.getRecordedById().equals(currentUser.getId())) {
            throw new BusinessException("You recorded this payment, so another Owner or Admin must approve or reject it.");
        }

        if (newStatus == ExpenseApprovalStatus.APPROVED) {
            BigDecimal outstanding = outstandingForPaymentScope(
                    payment.getClientId(), paymentScopeClientIds(payment.getClientId()));
            if (payment.getAmount().compareTo(outstanding) > 0) {
                throw new BusinessException(
                        "Approving this payment (" + payment.getAmount() + ") would exceed the current outstanding balance ("
                                + outstanding + "). Reject it or record a smaller payment.");
            }
        }

        payment.setPaymentApprovalStatus(newStatus);
        payment.setApprovedByUserId(currentUser.getId());
        payment.setApprovedAt(businessClock.now());
        return paymentRepository.save(payment);
    }

    /** Serialises payment recording and approval for one parent / sub-account group. */
    private void lockPaymentGroup(CreditClient client) {
        Long holderId = client.getParentClientId() != null ? client.getParentClientId() : client.getId();
        clientRepository.findByIdForUpdate(holderId);
    }

    private List<Long> paymentScopeClientIds(Long clientId) {
        List<Long> ids = new ArrayList<>();
        ids.add(clientId);
        clientRepository.findByParentClientId(clientId).forEach(child -> ids.add(child.getId()));
        return ids;
    }

    private BigDecimal outstandingForPaymentScope(Long clientId, List<Long> scopeClientIds) {
        return scopeClientIds.size() > 1
                ? interestService.computeOutstandingWithChildren(clientId)
                : interestService.computeOutstanding(clientId);
    }

    public String resolveRecordedByName(Long userId) {
        return userRepository.findById(userId)
                .map(User::getFullName)
                .orElse("Unknown");
    }

    private void validateGroupMove(Long pumpId, Long fromClientId, Long toClientId) {
        if (fromClientId == null) {
            return;
        }

        CreditClient fromClient = clientRepository.findById(fromClientId)
                .orElseThrow(() -> new ResourceNotFoundException("Source credit client not found"));
        CreditClient toClient = clientRepository.findById(toClientId)
                .orElseThrow(() -> new ResourceNotFoundException("Target credit client not found"));

        if (!fromClient.getPumpId().equals(pumpId) || !toClient.getPumpId().equals(pumpId)) {
            throw new BusinessException("Credit accounts do not belong to this pump");
        }

        Long fromGroupRoot = fromClient.getParentClientId() != null ? fromClient.getParentClientId() : fromClient.getId();
        Long toGroupRoot = toClient.getParentClientId() != null ? toClient.getParentClientId() : toClient.getId();

        if (!fromGroupRoot.equals(toGroupRoot)) {
            throw new BusinessException("Transactions can only be moved within the same parent / sub-account group");
        }
    }
}
