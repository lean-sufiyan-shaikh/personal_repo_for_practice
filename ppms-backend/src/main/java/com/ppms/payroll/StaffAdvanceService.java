package com.ppms.payroll;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A cash advance leaves the safe the day it is given, like any other payout, and is taken back from the staff member's
 * later payroll, oldest advance first, as far as the pay allows. The staff row lock serialises advances, voids and
 * payroll runs for one person, so a recovery never takes more than is outstanding.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAdvanceService {

    private static final String ENTITY_NAME = "StaffAdvance";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final StaffAdvanceRepository advanceRepository;
    private final StaffAdvanceRecoveryRepository recoveryRepository;
    private final UserRepository userRepository;
    private final CashDrawerService cashDrawerService;
    private final ClosedDayGuard closedDayGuard;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    @Transactional
    public StaffAdvanceResponses.AdvanceResponse giveAdvance(Long pumpId, GiveStaffAdvanceRequest request, User currentUser) {
        User staff = lockStaffOfPump(pumpId, request.staffUserId());
        if (request.advanceDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.STAFF_ADVANCE_DATE_IN_FUTURE, Map.of(),
                    "An advance cannot be dated in the future.");
        }
        closedDayGuard.requireOpen(pumpId, request.advanceDate());
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        CashEvent cashOut = cashDrawerService.recordAutomaticCashOut(pumpId, amount, request.advanceDate(),
                "Cash advance to " + staff.getFullName(), currentUser);
        StaffAdvance advance = advanceRepository.save(StaffAdvance.builder()
                .pumpId(pumpId)
                .staffUserId(staff.getId())
                .amount(amount)
                .advanceDate(request.advanceDate())
                .note(request.note() == null || request.note().isBlank() ? null : request.note().trim())
                .cashEventId(cashOut.getId())
                .givenByUserId(currentUser.getId())
                .build());
        auditService.log(pumpId, AuditAction.STAFF_ADVANCE_GIVEN, ENTITY_NAME, advance.getId().toString(),
                "Cash advance of ₹" + amount + " to " + staff.getFullName() + " on " + advance.getAdvanceDate(), currentUser);
        log.info("Staff advance given: pump={}, advance={}, staff={}, amount={}, by={}", pumpId, advance.getId(),
                staff.getId(), amount, currentUser.getId());
        return toResponses(List.of(advance)).get(0);
    }

    /** Only an advance payroll has not yet taken from can be voided; its cash goes back into the safe. */
    @Transactional
    public StaffAdvanceResponses.AdvanceResponse voidAdvance(Long pumpId, Long advanceId, String reason, User currentUser) {
        StaffAdvance advance = advanceRepository.findByIdAndPumpId(advanceId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.STAFF_ADVANCE, "Advance not found"));
        lockStaffOfPump(pumpId, advance.getStaffUserId());
        if (advance.getStatus() == StaffAdvanceStatus.VOIDED) {
            throw new BusinessException(ErrorCodes.STAFF_ADVANCE_ALREADY_VOIDED, Map.of(), "This advance is already voided.");
        }
        BigDecimal recovered = recoveryRepository.sumRecoveredOf(advanceId);
        if (recovered.signum() > 0) {
            throw new BusinessException(ErrorCodes.STAFF_ADVANCE_ALREADY_RECOVERED,
                    Map.of("recovered", recovered.setScale(2, RoundingMode.HALF_UP).toPlainString()),
                    "Payroll has already taken ₹" + recovered.setScale(2, RoundingMode.HALF_UP)
                            + " of this advance back, so it cannot be voided. Delete that draft payroll first.");
        }
        closedDayGuard.requireOpen(pumpId, advance.getAdvanceDate());
        String trimmedReason = reason.trim();
        advance.setStatus(StaffAdvanceStatus.VOIDED);
        advance.setVoidReason(trimmedReason);
        advance.setVoidedByUserId(currentUser.getId());
        advance.setVoidedAt(businessClock.now());
        advance = advanceRepository.save(advance);
        cashDrawerService.reverseAutomaticEvent(pumpId, advance.getCashEventId(),
                "Advance #" + advance.getId() + " voided: " + trimmedReason, currentUser);
        auditService.log(pumpId, AuditAction.STAFF_ADVANCE_VOIDED, ENTITY_NAME, advance.getId().toString(),
                "Voided cash advance of ₹" + advance.getAmount() + " given on " + advance.getAdvanceDate()
                        + ". Reason: " + trimmedReason, currentUser);
        log.info("Staff advance voided: pump={}, advance={}, by={}", pumpId, advanceId, currentUser.getId());
        return toResponses(List.of(advance)).get(0);
    }

    /** Every advance of the pump, or of one staff member, newest first, with what each staff member still owes. */
    @Transactional(readOnly = true)
    public StaffAdvanceResponses.AdvanceListResponse listAdvances(Long pumpId, Long staffUserId) {
        List<StaffAdvance> advances = staffUserId == null
                ? advanceRepository.findByPumpIdOrderByAdvanceDateDescIdDesc(pumpId)
                : advanceRepository.findByPumpIdAndStaffUserIdOrderByAdvanceDateDescIdDesc(pumpId, staffUserId);
        List<StaffAdvanceResponses.AdvanceResponse> responses = toResponses(advances);
        Map<Long, List<StaffAdvanceResponses.AdvanceResponse>> byStaff = responses.stream()
                .collect(Collectors.groupingBy(StaffAdvanceResponses.AdvanceResponse::staffUserId));
        List<StaffAdvanceResponses.StaffOutstanding> outstanding = byStaff.values().stream()
                .map(list -> new StaffAdvanceResponses.StaffOutstanding(list.get(0).staffUserId(), list.get(0).staffName(),
                        list.stream().map(StaffAdvanceResponses.AdvanceResponse::outstanding).reduce(ZERO, BigDecimal::add)))
                .sorted(Comparator.comparing(StaffAdvanceResponses.StaffOutstanding::outstanding).reversed()
                        .thenComparing(StaffAdvanceResponses.StaffOutstanding::staffUserId))
                .toList();
        return new StaffAdvanceResponses.AdvanceListResponse(responses, outstanding);
    }

    /**
     * Takes back at most {@code available} of the staff member's outstanding advances for a payroll, oldest first, and
     * records each part against the payroll. The caller holds the staff row lock and has saved the payroll.
     */
    BigDecimal recoverForPayroll(PayrollRecord payroll, BigDecimal available) {
        if (available.signum() <= 0) {
            return ZERO;
        }
        List<StaffAdvance> active = advanceRepository.findByPumpIdAndStaffUserIdAndStatusOrderByAdvanceDateAscIdAsc(
                payroll.getPumpId(), payroll.getUserId(), StaffAdvanceStatus.ACTIVE);
        Map<Long, BigDecimal> recoveredById = recoveredByAdvanceId(active.stream().map(StaffAdvance::getId).toList());
        BigDecimal left = available;
        List<StaffAdvanceRecovery> recoveries = new ArrayList<>();
        for (StaffAdvance advance : active) {
            BigDecimal outstanding = advance.getAmount().subtract(recoveredById.getOrDefault(advance.getId(), ZERO));
            BigDecimal taken = outstanding.min(left);
            if (taken.signum() > 0) {
                recoveries.add(StaffAdvanceRecovery.builder()
                        .advanceId(advance.getId())
                        .payrollId(payroll.getId())
                        .amount(taken)
                        .build());
                left = left.subtract(taken);
            }
            if (left.signum() == 0) {
                break;
            }
        }
        recoveryRepository.saveAll(recoveries);
        BigDecimal total = available.subtract(left);
        log.info("Advances recovered by payroll {}: staff={}, amount={}, advances={}", payroll.getId(), payroll.getUserId(),
                total, recoveries.size());
        return total;
    }

    private User lockStaffOfPump(Long pumpId, Long staffUserId) {
        return userRepository.findByIdForUpdate(staffUserId)
                .filter(found -> pumpId.equals(found.getAssignedPumpId()) && found.getRole() != UserRole.OWNER)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.USER, "Staff member not found"));
    }

    private Map<Long, BigDecimal> recoveredByAdvanceId(List<Long> advanceIds) {
        if (advanceIds.isEmpty()) {
            return Map.of();
        }
        return recoveryRepository.findByAdvanceIdIn(advanceIds).stream()
                .collect(Collectors.groupingBy(StaffAdvanceRecovery::getAdvanceId,
                        Collectors.reducing(ZERO, StaffAdvanceRecovery::getAmount, BigDecimal::add)));
    }

    private List<StaffAdvanceResponses.AdvanceResponse> toResponses(List<StaffAdvance> advances) {
        Map<Long, BigDecimal> recoveredById = recoveredByAdvanceId(advances.stream().map(StaffAdvance::getId).toList());
        Set<Long> userIds = new HashSet<>();
        advances.forEach(advance -> {
            userIds.add(advance.getStaffUserId());
            userIds.add(advance.getGivenByUserId());
        });
        Map<Long, String> nameById = userIds.isEmpty() ? Map.of() : userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));
        return advances.stream()
                .map(advance -> toResponse(advance, recoveredById.getOrDefault(advance.getId(), ZERO), nameById))
                .toList();
    }

    private static StaffAdvanceResponses.AdvanceResponse toResponse(StaffAdvance advance, BigDecimal recovered,
                                                                   Map<Long, String> nameById) {
        BigDecimal outstanding = advance.getStatus() == StaffAdvanceStatus.VOIDED
                ? ZERO : advance.getAmount().subtract(recovered);
        return new StaffAdvanceResponses.AdvanceResponse(advance.getId(), advance.getStaffUserId(),
                nameById.getOrDefault(advance.getStaffUserId(), "Unknown"), advance.getAmount(), advance.getAdvanceDate(),
                advance.getNote(), recovered.setScale(2, RoundingMode.HALF_UP), outstanding.setScale(2, RoundingMode.HALF_UP),
                advance.getStatus().name(), nameById.getOrDefault(advance.getGivenByUserId(), "Unknown"),
                advance.getVoidReason(), advance.getVoidedAt(), advance.getCreatedAt());
    }
}
