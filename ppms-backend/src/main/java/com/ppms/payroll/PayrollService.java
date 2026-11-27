package com.ppms.payroll;

import com.ppms.cash.CashDrawerService;
import com.ppms.cash.CashEvent;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.planning.StaffLeaveRepository;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.report.StaleReasons;
import com.ppms.shift.DiscrepancyResolution;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftOperator;
import com.ppms.shift.ShiftOperatorRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollService {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final String ENTITY_NAME = "PayrollRecord";

    private final PayrollRepository              payrollRepository;
    private final PayrollResolvedShiftRepository resolvedShiftRepository;
    private final UserRepository                 userRepository;
    private final ShiftRepository                shiftRepository;
    private final StaffLeaveRepository           staffLeaveRepository;
    private final AuditService                   auditService;
    private final BusinessClock businessClock;
    private final ClosedDayGuard                 closedDayGuard;
    private final CashDrawerService              cashDrawerService;
    private final BalanceSheetStalenessService   balanceSheetStalenessService;
    private final StaffAdvanceService            staffAdvanceService;
    private final ShiftOperatorRepository        shiftOperatorRepository;

    /**
     * Resolves the selected discrepancies, computes the draft and saves it in one transaction,
     * so a failure (e.g. a duplicate period) leaves no shift resolved without a payroll record.
     * The draft takes every salary deduction of the staff member not taken by an earlier payroll, whatever its date,
     * and the deductions payroll of earlier periods could not take from its pay.
     */
    @Transactional
    public PayrollRecord generate(Long pumpId, GeneratePayrollRequest req, User currentUser) {
        if (req.getPeriodTo().isBefore(req.getPeriodFrom())) {
            throw new BusinessException(ErrorCodes.PAYROLL_PERIOD_END_BEFORE_START,
                    Map.of(),
                    "periodTo must be on or after periodFrom");
        }

        // The staff row lock serialises payroll generation per person, so two overlapping runs cannot both pass the check.
        User staff = userRepository.findByIdForUpdate(req.getUserId())
                .filter(found -> pumpId.equals(found.getAssignedPumpId()))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.USER, "Staff member not found"));

        payrollRepository.findFirstByPumpIdAndUserIdAndPeriodFromLessThanEqualAndPeriodToGreaterThanEqual(
                        pumpId, req.getUserId(), req.getPeriodTo(), req.getPeriodFrom())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCodes.PAYROLL_PERIOD_OVERLAPS,
                            Map.of("staffName", staff.getFullName(),
                                    "existingFrom", existing.getPeriodFrom().toString(),
                                    "existingTo", existing.getPeriodTo().toString(),
                                    "periodFrom", req.getPeriodFrom().toString(),
                                    "periodTo", req.getPeriodTo().toString()),
                            "A payroll record for " + staff.getFullName() + " already covers "
                            + existing.getPeriodFrom() + " to " + existing.getPeriodTo()
                            + ", which overlaps " + req.getPeriodFrom() + " to " + req.getPeriodTo() + ".");
                });

        Map<Long, ShiftStatus> previousStatusByShiftId = resolveSelectedDiscrepancies(pumpId, req, currentUser);
        List<Shift> deductionShifts = shiftRepository.findUntakenSalaryDeductionShifts(pumpId, req.getUserId());
        List<PayrollRecord> withUncarriedDeductions =
                payrollRepository.findWithUncarriedDeductionsBefore(pumpId, req.getUserId(), req.getPeriodFrom());

        PayrollRecord record = staff.getRole() == UserRole.OPERATOR
                ? buildHourlyShiftPayroll(pumpId, req, staff)
                : buildDailyPayroll(pumpId, req, staff);
        applyDeductions(record, deductionShifts, withUncarriedDeductions);

        PayrollRecord saved = payrollRepository.save(record);
        resolvedShiftRepository.saveAll(deductionShifts.stream()
                .map(shift -> PayrollResolvedShift.builder()
                        .payrollId(saved.getId())
                        .shiftId(shift.getId())
                        .previousStatus(previousStatusByShiftId.get(shift.getId()))
                        .build())
                .toList());
        withUncarriedDeductions.forEach(earlier -> earlier.setCarriedToPayrollId(saved.getId()));
        payrollRepository.saveAll(withUncarriedDeductions);
        recoverAdvances(saved);

        log.info("Payroll generated: pump={} user={} role={} type={} period={}/{} gross={} deductions={} opening={} advances={} status=DRAFT",
                pumpId, req.getUserId(), staff.getRole(), saved.getSalaryType(),
                req.getPeriodFrom(), req.getPeriodTo(), saved.getGrossAmount(), saved.getDeductions(),
                saved.getOpeningDeductions(), saved.getAdvanceRecovered());
        return saved;
    }

    /** Outstanding advances come off what is left of the pay after deductions, never taking net pay below zero. */
    private void recoverAdvances(PayrollRecord payroll) {
        BigDecimal recovered = staffAdvanceService.recoverForPayroll(payroll, payroll.getNetPay());
        if (recovered.signum() > 0) {
            payroll.setAdvanceRecovered(recovered);
            payroll.setNetPay(payroll.getNetPay().subtract(recovered));
            payrollRepository.save(payroll);
        }
    }

    /**
     * Moves a payroll record along DRAFT → APPROVED → PAID, or back from APPROVED to DRAFT, auditing each move.
     * Marking it PAID records the business date, mode and reference of the payment; a CASH payment is taken out of the
     * safe on that date. The day must still be open, since its balance sheet counts the payroll paid that day.
     */
    @Transactional
    public PayrollRecord changeStatus(Long pumpId, Long recordId, UpdatePayrollStatusRequest request, User currentUser) {
        PayrollStatus newStatus = parseStatus(request.status());
        PayrollRecord record = payrollRepository.findById(recordId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PAYROLL_RECORD,
                        "Payroll record not found"));

        boolean valid = (record.getStatus() == PayrollStatus.DRAFT && newStatus == PayrollStatus.APPROVED)
                     || (record.getStatus() == PayrollStatus.APPROVED && newStatus == PayrollStatus.PAID)
                     || (record.getStatus() == PayrollStatus.APPROVED && newStatus == PayrollStatus.DRAFT);
        if (!valid) {
            throw new BusinessException(ErrorCodes.PAYROLL_STATUS_TRANSITION_INVALID,
                    Map.of("fromStatusCode", record.getStatus().name(), "toStatusCode", newStatus.name()),
                    "Invalid transition: " + record.getStatus() + " → " + newStatus);
        }

        String description;
        AuditAction action;
        if (newStatus == PayrollStatus.APPROVED) {
            record.setApprovedBy(currentUser.getId());
            action = AuditAction.PAYROLL_APPROVED;
            description = "Payroll approved: recordId=" + recordId + " grossAmount=₹" + record.getGrossAmount();
        } else if (newStatus == PayrollStatus.DRAFT) {
            action = AuditAction.PAYROLL_RETURNED_TO_DRAFT;
            description = "Payroll returned to draft: recordId=" + recordId + " grossAmount=₹" + record.getGrossAmount();
        } else {
            markPaid(record, request, currentUser);
            action = AuditAction.PAYROLL_PAID;
            description = "Payroll paid: recordId=" + recordId + " netPay=₹" + record.getNetPay() + " on "
                    + record.getPaidDate() + " by " + record.getPaymentMode()
                    + (record.getPaymentReference() != null ? " ref=" + record.getPaymentReference() : "");
        }
        record.setStatus(newStatus);
        PayrollRecord saved = payrollRepository.save(record);
        auditService.log(pumpId, action, ENTITY_NAME, recordId.toString(), description, currentUser);
        log.info("Payroll status changed: pump={} record={} status={} by={}", pumpId, recordId, newStatus,
                currentUser.getId());
        return saved;
    }

    private static PayrollStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BusinessException(ErrorCodes.PAYROLL_STATUS_REQUIRED, Map.of(), "status is required");
        }
        try {
            return PayrollStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCodes.PAYROLL_STATUS_INVALID, Map.of(),
                    "Invalid status '" + status + "'. Allowed: DRAFT, APPROVED, PAID");
        }
    }

    private void markPaid(PayrollRecord record, UpdatePayrollStatusRequest request, User currentUser) {
        if (request.paidDate() == null || request.paymentMode() == null) {
            throw new BusinessException(ErrorCodes.PAYROLL_PAYMENT_DETAILS_REQUIRED, Map.of(),
                    "Enter the date the salary was paid and how it was paid.");
        }
        if (request.paidDate().isAfter(businessClock.today())) {
            throw new BusinessException(ErrorCodes.PAYROLL_PAID_DATE_IN_FUTURE, Map.of(),
                    "The paid date cannot be in the future.");
        }
        closedDayGuard.requireOpen(record.getPumpId(), request.paidDate());

        record.setPaidDate(request.paidDate());
        record.setPaymentMode(request.paymentMode());
        record.setPaymentReference(request.paymentReference() == null || request.paymentReference().isBlank()
                ? null : request.paymentReference().trim());
        record.setPaidBy(currentUser.getId());
        if (request.paymentMode() == PayrollPaymentMode.CASH && record.getNetPay().signum() > 0) {
            String staffName = userRepository.findById(record.getUserId()).map(User::getFullName).orElse("staff");
            CashEvent cashOut = cashDrawerService.recordAutomaticCashOut(record.getPumpId(), record.getNetPay(),
                    request.paidDate(), "Salary paid to " + staffName + " for " + record.getPeriodFrom() + " to "
                            + record.getPeriodTo() + " (payroll #" + record.getId() + ")", currentUser);
            record.setCashEventId(cashOut.getId());
        }
        balanceSheetStalenessService.markDaySheetsStale(record.getPumpId(), List.of(request.paidDate()),
                StaleReasons.payrollPaid(record.getId()));
    }

    /**
     * Deletes a DRAFT payroll and restores the discrepancies it resolved to their earlier pending status,
     * so the deduction can be taken again in a later pay run. Deductions it took that were chosen on the shift, and
     * those it brought in from earlier payroll, are left for the next payroll to take.
     */
    @Transactional
    public void deleteDraft(Long pumpId, Long recordId, User currentUser) {
        PayrollRecord record = payrollRepository.findById(recordId)
                .filter(found -> found.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PAYROLL_RECORD,
                        "Payroll record not found"));
        if (record.getStatus() != PayrollStatus.DRAFT) {
            throw new BusinessException(ErrorCodes.PAYROLL_DELETE_ONLY_DRAFT,
                    Map.of(),
                    "Only draft payroll records can be deleted.");
        }
        if (record.getCarriedToPayrollId() != null) {
            throw new BusinessException(ErrorCodes.PAYROLL_DEDUCTIONS_CARRIED_FORWARD,
                    Map.of("carriedToPayrollId", record.getCarriedToPayrollId()),
                    "The deductions this payroll could not take were carried into payroll #"
                            + record.getCarriedToPayrollId() + ". Delete that draft first.");
        }

        for (PayrollResolvedShift link : resolvedShiftRepository.findByPayrollId(recordId)) {
            if (link.getPreviousStatus() != null) {
                restoreDiscrepancy(link);
            }
        }

        payrollRepository.delete(record);
        auditService.log(pumpId, AuditAction.PAYROLL_DELETED,
                ENTITY_NAME, recordId.toString(),
                "Payroll draft deleted: recordId=" + recordId + " grossAmount=₹" + record.getGrossAmount(),
                currentUser);
    }

    private void restoreDiscrepancy(PayrollResolvedShift link) {
        Shift shift = shiftRepository.findByIdForUpdate(link.getShiftId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHIFT,
                        "Shift #" + link.getShiftId() + " not found"));
        boolean stillResolvedByPayroll = shift.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_RESOLVED
                && shift.getDiscrepancyResolution() == DiscrepancyResolution.SALARY_DEDUCTION;
        if (!stillResolvedByPayroll) {
            throw new BusinessException(ErrorCodes.PAYROLL_SHIFT_CHANGED_SINCE_GENERATION,
                    Map.of("shiftId", shift.getId(), "statusCode", shift.getStatus().name()),
                    "Shift #" + shift.getId() + " was changed after this payroll was generated ("
                    + shift.getStatus() + "); resolve it manually before deleting the draft.");
        }
        shift.setStatus(link.getPreviousStatus());
        shift.setDiscrepancyResolution(null);
        shift.setDiscrepancyResolvedById(null);
        shift.setDiscrepancyResolvedAt(null);
        shiftRepository.save(shift);
    }

    /** Resolves the selected discrepancies as salary deductions; returns the status each shift had before. */
    private Map<Long, ShiftStatus> resolveSelectedDiscrepancies(Long pumpId, GeneratePayrollRequest req, User resolvedBy) {
        List<Long> ids = req.getDeductFromSalaryShiftIds();
        Map<Long, ShiftStatus> previousStatusByShiftId = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return previousStatusByShiftId;
        }

        for (Long shiftId : ids.stream().distinct().sorted().toList()) {
            Shift shift = shiftRepository.findByIdForUpdate(shiftId)
                    .filter(found -> found.getPumpId().equals(pumpId) && found.getOperatorId().equals(req.getUserId()))
                    .orElseThrow(() -> new BusinessException(ErrorCodes.PAYROLL_SHIFT_NOT_OF_PUMP_OR_OPERATOR,
                            Map.of("shiftId", shiftId),
                            "Shift #" + shiftId + " does not belong to this pump / operator."));
            boolean isPending = shift.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_PENDING
                    || shift.getStatus() == ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL;
            if (!isPending) {
                throw new BusinessException(ErrorCodes.PAYROLL_SHIFT_DISCREPANCY_NOT_PENDING,
                        Map.of("shiftId", shiftId),
                        "Shift #" + shiftId + " is not in a pending discrepancy state.");
            }
            if (shift.getDiscrepancyType() != DiscrepancyType.SHORT) {
                throw new BusinessException(ErrorCodes.PAYROLL_SHIFT_DISCREPANCY_IS_OVER, Map.of("shiftId", shiftId),
                        "Shift #" + shiftId + " has an OVER discrepancy; only SHORT discrepancies " +
                        "can be deducted from salary.");
            }

            previousStatusByShiftId.put(shiftId, shift.getStatus());
            shift.setDiscrepancyResolution(DiscrepancyResolution.SALARY_DEDUCTION);
            shift.setDiscrepancyResolvedById(resolvedBy.getId());
            shift.setDiscrepancyResolvedAt(businessClock.now());
            shift.setStatus(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
            shiftRepository.save(shift);
        }
        shiftRepository.flush();
        return previousStatusByShiftId;
    }

    /** Hourly pay for OPERATOR: worked minutes × shift-window hourly rate, rounded once. */
    private PayrollRecord buildHourlyShiftPayroll(Long pumpId, GeneratePayrollRequest req, User staff) {
        BigDecimal shift1Rate = staff.getShift1HourlyRate();
        BigDecimal standardRate = staff.getStandardHourlyRate();
        if (shift1Rate == null || shift1Rate.compareTo(BigDecimal.ZERO) <= 0
                || standardRate == null || standardRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCodes.PAYROLL_HOURLY_RATES_MISSING,
                    Map.of("staffName", staff.getFullName()),
                    staff.getFullName() + " does not have hourly rates configured. " +
                    "Set their Shift 1 (night) and standard hourly rates in Setup → Staff before generating payroll.");
        }

        List<Shift> closedShifts = shiftRepository.findByPumpIdAndOperatorIdAndDateRange(
                        pumpId, req.getUserId(), req.getPeriodFrom(), req.getPeriodTo())
                .stream()
                .filter(shift -> shift.getActualEndTime() != null)
                .toList();

        long shift1Minutes = 0;
        long standardMinutes = 0;
        int shift1Count = 0;
        int standardCount = 0;
        for (Shift shift : closedShifts) {
            long minutes = Duration.between(shift.getActualStartTime(), shift.getActualEndTime()).toMinutes();
            if (Boolean.TRUE.equals(shift.getIsNightShift())) {
                shift1Minutes += minutes;
                shift1Count++;
            } else {
                standardMinutes += minutes;
                standardCount++;
            }
        }
        List<ShiftOperator> otherShiftWork = shiftOperatorRepository.findFinishedWorkOnOtherShifts(
                pumpId, req.getUserId(), req.getPeriodFrom(), req.getPeriodTo());
        Map<Long, Shift> otherShiftById = otherShiftWork.isEmpty() ? Map.of()
                : shiftRepository.findAllById(otherShiftWork.stream().map(ShiftOperator::getShiftId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(Shift::getId, Function.identity()));
        for (ShiftOperator work : otherShiftWork) {
            long minutes = Duration.between(work.getJoinedAt(), work.getLeftAt()).toMinutes();
            if (Boolean.TRUE.equals(otherShiftById.get(work.getShiftId()).getIsNightShift())) {
                shift1Minutes += minutes;
                shift1Count++;
            } else {
                standardMinutes += minutes;
                standardCount++;
            }
        }

        BigDecimal gross = BigDecimal.valueOf(shift1Minutes).multiply(shift1Rate)
                .add(BigDecimal.valueOf(standardMinutes).multiply(standardRate))
                .divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);

        return PayrollRecord.builder()
                .salaryType("HOURLY_SHIFT")
                .pumpId(pumpId)
                .userId(req.getUserId())
                .periodFrom(req.getPeriodFrom())
                .periodTo(req.getPeriodTo())
                .totalShifts(shift1Count + standardCount)
                .shift1Shifts(shift1Count).shift1Hours(toHours(shift1Minutes)).shift1RateSnapshot(shift1Rate)
                .standardShifts(standardCount).standardHours(toHours(standardMinutes)).standardRateSnapshot(standardRate)
                .grossAmount(gross)
                .notes(req.getNotes())
                .status(PayrollStatus.DRAFT)
                .build();
    }

    /** Daily pay for MANAGER / ADMIN / ACCOUNTANT: (calendar days − leave days) × daily rate. */
    private PayrollRecord buildDailyPayroll(Long pumpId, GeneratePayrollRequest req, User staff) {
        BigDecimal dailyRate = staff.getDailyRate();
        if (dailyRate == null || dailyRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCodes.PAYROLL_DAILY_RATE_MISSING, Map.of("staffName", staff.getFullName()),
                    staff.getFullName() + " does not have a daily rate configured. " +
                    "Set their daily rate in Setup → Staff before generating payroll.");
        }

        int totalDays = (int) ChronoUnit.DAYS.between(req.getPeriodFrom(), req.getPeriodTo()) + 1;
        int leaveDays = (int) staffLeaveRepository.countByUserIdAndLeaveDateBetween(
                req.getUserId(), req.getPeriodFrom(), req.getPeriodTo());
        int daysWorked = Math.max(0, totalDays - leaveDays);

        return PayrollRecord.builder()
                .salaryType("DAILY")
                .pumpId(pumpId)
                .userId(req.getUserId())
                .periodFrom(req.getPeriodFrom())
                .periodTo(req.getPeriodTo())
                .totalShifts(0)
                .shift1Shifts(0).shift1Hours(BigDecimal.ZERO)
                .standardShifts(0).standardHours(BigDecimal.ZERO)
                .totalDays(totalDays)
                .leaveDays(leaveDays)
                .daysWorked(daysWorked)
                .dailyRateSnapshot(dailyRate)
                .grossAmount(dailyRate.multiply(BigDecimal.valueOf(daysWorked)).setScale(2, RoundingMode.HALF_UP))
                .notes(req.getNotes())
                .status(PayrollStatus.DRAFT)
                .build();
    }

    private void applyDeductions(PayrollRecord record, List<Shift> deductionShifts,
                                 List<PayrollRecord> withUncarriedDeductions) {
        BigDecimal shiftDeductions = deductionShifts.stream()
                .map(Shift::shortageNotRecovered)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openingDeductions = withUncarriedDeductions.stream()
                .map(PayrollRecord::getUncoveredDeductions)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal deductions = shiftDeductions.add(openingDeductions).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netBeforeFloor = record.getGrossAmount().subtract(deductions);

        record.setOpeningDeductions(openingDeductions);
        record.setDeductions(deductions);
        record.setNetPay(netBeforeFloor.max(BigDecimal.ZERO));
        record.setUncoveredDeductions(netBeforeFloor.negate().max(BigDecimal.ZERO));

        if (record.getUncoveredDeductions().signum() > 0) {
            log.warn("Payroll deductions exceed gross pay and carry forward: pump={} user={} period={}/{} gross={} deductions={} uncovered={}",
                    record.getPumpId(), record.getUserId(), record.getPeriodFrom(), record.getPeriodTo(),
                    record.getGrossAmount(), deductions, record.getUncoveredDeductions());
        }
    }

    private static BigDecimal toHours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(MINUTES_PER_HOUR, 2, RoundingMode.HALF_UP);
    }
}
