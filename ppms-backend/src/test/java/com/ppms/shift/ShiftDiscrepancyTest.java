package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.dayclose.ClosedDayGuard;
import com.ppms.report.BalanceSheetStalenessService;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftDiscrepancyTest {

    private static final long PUMP_ID = 4L;
    private static final long SHIFT_ID = 30L;
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

    @Mock private ShiftRepository shiftRepository;
    @Mock private ShiftLifecycleSupportService lifecycleSupportService;
    @Mock private ShiftReadModelService shiftReadModelService;
    @Mock private BusinessClock businessClock;
    @Mock private ClosedDayGuard closedDayGuard;
    @Mock private ShiftInvestigationNoteRepository investigationNoteRepository;
    @Mock private BalanceSheetStalenessService balanceSheetStalenessService;
    @InjectMocks private ShiftService shiftService;

    private final User owner = User.builder().id(1L).fullName("Owner").role(UserRole.OWNER).build();
    private final User manager = User.builder().id(2L).fullName("Manager").role(UserRole.MANAGER).assignedPumpId(PUMP_ID).build();
    private final User otherManager = User.builder().id(3L).fullName("Other").role(UserRole.MANAGER).assignedPumpId(PUMP_ID).build();

    @BeforeEach
    void stubSavesAndClock() {
        lenient().when(shiftRepository.save(any(Shift.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(businessClock.now()).thenReturn(NOW);
        lenient().when(businessClock.today()).thenReturn(NOW.toLocalDate());
    }

    @Test
    void waiverWithoutANoteIsRefused() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        assertRefused(shift, request(DiscrepancyResolution.WAIVED, " ", null), owner, "WAIVER_REASON_REQUIRED");
    }

    @Test
    void waiverWithANoteResolvesTheShift() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        resolve(shift, request(DiscrepancyResolution.WAIVED, "Owner agreed", null), owner);

        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        assertThat(shift.getDiscrepancyResolution()).isEqualTo(DiscrepancyResolution.WAIVED);
        assertThat(shift.getDiscrepancyResolutionNote()).isEqualTo("Owner agreed");
        assertThat(shift.getDiscrepancyResolvedById()).isEqualTo(owner.getId());
    }

    @Test
    void managerWhoClosedTheShiftCannotWaiveItsDiscrepancy() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        assertRefused(shift, request(DiscrepancyResolution.WAIVED, "Small amount", null), manager,
                "WAIVER_BY_CLOSER_NOT_ALLOWED");
    }

    @Test
    void anotherManagerOrTheOwnerCanWaiveWhatAManagerClosed() {
        Shift byOtherManager = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        resolve(byOtherManager, request(DiscrepancyResolution.WAIVED, "Small amount", null), otherManager);
        Shift byOwner = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        resolve(byOwner, request(DiscrepancyResolution.WAIVED, "Small amount", null), owner);

        assertThat(byOtherManager.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        assertThat(byOwner.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
    }

    @Test
    void investigationNeedsANote() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        assertRefused(shift, request(DiscrepancyResolution.PENDING_INVESTIGATION, null, null), manager,
                "INVESTIGATION_NOTE_REQUIRED");
    }

    @Test
    void investigationKeepsTheShortagePendingAndRemembersWhoStartedIt() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        resolve(shift, request(DiscrepancyResolution.PENDING_INVESTIGATION, "Checking CCTV", null), manager);

        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(shift.getDiscrepancyResolution()).isNull();
        assertThat(shift.getInvestigationNote()).isEqualTo("Checking CCTV");
        assertThat(shift.getInvestigationStartedById()).isEqualTo(manager.getId());
        assertThat(shift.getInvestigationStartedAt()).isEqualTo(NOW);
    }

    @Test
    void everyInvestigationNoteIsKeptAndTheFirstInvestigatorStays() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        resolve(shift, request(DiscrepancyResolution.PENDING_INVESTIGATION, "Checking CCTV", null), manager);
        resolve(shift, request(DiscrepancyResolution.PENDING_INVESTIGATION, "CCTV shows a drive-off", null), owner);

        ArgumentCaptor<ShiftInvestigationNote> notes = ArgumentCaptor.forClass(ShiftInvestigationNote.class);
        verify(investigationNoteRepository, times(2)).save(notes.capture());
        assertThat(notes.getAllValues()).extracting(ShiftInvestigationNote::getNote)
                .containsExactly("Checking CCTV", "CCTV shows a drive-off");
        assertThat(notes.getAllValues()).extracting(ShiftInvestigationNote::getWrittenByUserId)
                .containsExactly(manager.getId(), owner.getId());
        assertThat(shift.getInvestigationNote()).isEqualTo("CCTV shows a drive-off");
        assertThat(shift.getInvestigationStartedById()).isEqualTo(manager.getId());
    }

    @Test
    void managerCannotDecideAnEscalatedDiscrepancy() {
        Shift shift = closedShort("500.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);

        assertRefused(shift, request(DiscrepancyResolution.WAIVED, "Let it go", null), otherManager,
                "ESCALATED_DISCREPANCY_NEEDS_OWNER");
    }

    @Test
    void ownerInvestigatingAnEscalatedDiscrepancyKeepsItAwaitingApprovalUntilTheFinalAction() {
        Shift shift = closedShort("500.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);

        resolve(shift, request(DiscrepancyResolution.PENDING_INVESTIGATION, "Bank deposit check", null), owner);
        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING_APPROVAL);

        resolve(shift, request(DiscrepancyResolution.SALARY_DEDUCTION, null, null), owner);
        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        assertThat(shift.getDiscrepancyResolution()).isEqualTo(DiscrepancyResolution.SALARY_DEDUCTION);
    }

    @Test
    void salaryDeductionIsRefusedForAnOverDiscrepancy() {
        Shift shift = closedShort("100.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        shift.setDiscrepancyType(DiscrepancyType.OVER);

        assertRefused(shift, request(DiscrepancyResolution.SALARY_DEDUCTION, null, null), otherManager,
                "SALARY_DEDUCTION_ONLY_FOR_SHORT");
        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(shift.getDiscrepancyType()).isEqualTo(DiscrepancyType.OVER);
    }

    @Test
    void fullCashRecoveryResolvesTheShortageAndPaysItInOnce() {
        Shift shift = closedShort("200.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        resolve(shift, request(DiscrepancyResolution.CASH_RECOVERY, "Operator paid back", null), manager);

        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
        assertThat(shift.getDiscrepancyResolution()).isEqualTo(DiscrepancyResolution.CASH_RECOVERY);
        assertThat(shift.getCashRecoveryAmount()).isEqualByComparingTo("200.00");
        verify(lifecycleSupportService, times(1)).createCashRecoveryEvent(shift, manager);
        verify(closedDayGuard).requireTodayOpen(PUMP_ID);
    }

    @Test
    void recoveryAboveTheShortageIsRefused() {
        Shift shift = closedShort("300.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        assertRefused(shift, request(DiscrepancyResolution.CASH_RECOVERY, null, new BigDecimal("400")), owner,
                "CASH_RECOVERY_AMOUNT_INVALID");
        verify(lifecycleSupportService, never()).createCashRecoveryEvent(any(), any());
    }

    @Test
    void partOfAShortageRecoveredLeavesTheRestPendingAndOnlyOneRecoveryIsAllowed() {
        Shift shift = closedShort("300.00", ShiftStatus.CLOSED_DISCREPANCY_PENDING);

        resolve(shift, request(DiscrepancyResolution.CASH_RECOVERY, null, new BigDecimal("100")), owner);

        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(shift.getCashRecoveryAmount()).isEqualByComparingTo("100.00");
        assertThat(shift.getDiscrepancyResolution()).isNull();
        assertThat(shift.shortageNotRecovered()).isEqualByComparingTo("200.00");
        assertRefused(shift, request(DiscrepancyResolution.CASH_RECOVERY, null, new BigDecimal("50")), owner,
                "CASH_RECOVERY_ALREADY_RECORDED");

        resolve(shift, request(DiscrepancyResolution.SALARY_DEDUCTION, null, null), owner);
        assertThat(shift.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);
    }

    @Test
    void closingShortOrOverWithoutAReasonIsRefused() {
        CloseShiftRequest request = new CloseShiftRequest();
        request.setDiscrepancyReason(" ");
        ShiftLifecycleSupportService.ClosingSummary shortBy100 = ShiftLifecycleSupportService.ClosingSummary.builder()
                .totalDue(new BigDecimal("1000.00")).totalCollected(new BigDecimal("900.00"))
                .discrepancyAmount(new BigDecimal("100.00")).discrepancyType(DiscrepancyType.SHORT).build();

        assertThatThrownBy(() -> ShiftService.requireReasonForDiscrepancy(request, shortBy100))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("DISCREPANCY_REASON_REQUIRED"));
    }

    @Test
    void balancedCloseNeedsNoReason() {
        ShiftLifecycleSupportService.ClosingSummary balanced = ShiftLifecycleSupportService.ClosingSummary.builder()
                .totalDue(new BigDecimal("1000.00")).totalCollected(new BigDecimal("999.50"))
                .discrepancyAmount(BigDecimal.ZERO).build();

        assertThatCode(() -> ShiftService.requireReasonForDiscrepancy(new CloseShiftRequest(), balanced))
                .doesNotThrowAnyException();
    }

    private void resolve(Shift shift, ResolveDiscrepancyRequest request, User user) {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(shift));
        shiftService.resolveDiscrepancy(PUMP_ID, SHIFT_ID, request, user);
    }

    private void assertRefused(Shift shift, ResolveDiscrepancyRequest request, User user, String code) {
        when(shiftRepository.findByIdAndPumpIdForUpdate(SHIFT_ID, PUMP_ID)).thenReturn(Optional.of(shift));
        assertThatThrownBy(() -> shiftService.resolveDiscrepancy(PUMP_ID, SHIFT_ID, request, user))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.getCode()).isEqualTo(code));
    }

    private static ResolveDiscrepancyRequest request(DiscrepancyResolution action, String note, BigDecimal recovered) {
        return new ResolveDiscrepancyRequest(action, note, recovered);
    }

    private Shift closedShort(String amount, ShiftStatus status) {
        return Shift.builder()
                .id(SHIFT_ID)
                .pumpId(PUMP_ID)
                .operatorId(7L)
                .shiftDate(LocalDate.of(2026, 11, 28))
                .status(status)
                .discrepancyType(DiscrepancyType.SHORT)
                .discrepancyAmount(new BigDecimal(amount))
                .closedByUserId(manager.getId())
                .build();
    }
}
