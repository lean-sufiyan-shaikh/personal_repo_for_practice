package com.ppms.user;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.auth.LoginRateLimiterService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.common.time.BusinessClock;
import com.ppms.planning.ShiftPlanningService;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.ShiftRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final long PUMP_ID = 4L;
    private static final String TEMPORARY_PASSWORD = "Temp@4321";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ShiftRepository shiftRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @Mock private LoginRateLimiterService loginRateLimiterService;
    @Mock private ShiftPlanningService shiftPlanningService;

    @InjectMocks private UserService userService;

    @Test
    void resetStoresTheNewHashEndsSessionsFlagsTheUserAndClearsTheLockout() {
        User admin = user(1L, UserRole.ADMIN, PUMP_ID);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(operator));
        when(passwordEncoder.encode(TEMPORARY_PASSWORD)).thenReturn("new-hash");

        userService.resetPassword(2L, resetRequest(), admin);

        assertThat(operator.getPasswordHash()).isEqualTo("new-hash");
        assertThat(operator.getTokenVersion()).isEqualTo(1);
        assertThat(operator.isMustChangePassword()).isTrue();
        verify(loginRateLimiterService).clearFailedLogins(operator.getPhoneNumber());
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.PASSWORD_RESET), eq("User"), eq("2"),
                description.capture(), eq(admin));
        assertThat(description.getValue()).doesNotContain(TEMPORARY_PASSWORD);
    }

    @Test
    void adminCannotResetAnotherAdmin() {
        User admin = user(1L, UserRole.ADMIN, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.ADMIN, PUMP_ID)));

        assertThatThrownBy(() -> userService.resetPassword(2L, resetRequest(), admin))
                .isInstanceOf(AccessDeniedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void ownerCannotResetStaffOfAPumpTheyDoNotOwn() {
        User owner = user(1L, UserRole.OWNER, null);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.OPERATOR, PUMP_ID)));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(false);

        assertThatThrownBy(() -> userService.resetPassword(2L, resetRequest(), owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void superAdminResetsOwnersOnly() {
        User superAdmin = user(1L, UserRole.SUPER_ADMIN, null);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.MANAGER, PUMP_ID)));

        assertThatThrownBy(() -> userService.resetPassword(2L, resetRequest(), superAdmin))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deactivationIsRefusedWhileTheUserHasAnOpenShift() {
        User owner = user(1L, UserRole.OWNER, null);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.OPERATOR, PUMP_ID)));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(shiftRepository.hasOpenShift(2L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUserStatus(2L, UserStatus.INACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("open shift");
        verify(userRepository, never()).save(any());
    }

    @Test
    void deactivationIsRefusedWhileADiscrepancyWaitsForApproval() {
        User owner = user(1L, UserRole.OWNER, null);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.OPERATOR, PUMP_ID)));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(shiftRepository.hasDiscrepancyPendingApproval(2L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUserStatus(2L, UserStatus.INACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("waiting for approval");
        verify(auditService, never()).log(any(), any(), anyString(), anyString(), anyString(), any(User.class));
    }

    @Test
    void deactivationIsRefusedWhileADiscrepancyIsUnresolved() {
        User owner = user(1L, UserRole.OWNER, null);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(2L, UserRole.OPERATOR, PUMP_ID)));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(shiftRepository.hasUnresolvedDiscrepancy(2L)).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUserStatus(2L, UserStatus.INACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unresolved shift discrepancies");
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCannotRaiseStaffToAdminOwnerOrSuperAdmin() {
        User admin = user(1L, UserRole.ADMIN, PUMP_ID);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(operator));

        for (UserRole role : List.of(UserRole.ADMIN, UserRole.OWNER, UserRole.SUPER_ADMIN)) {
            assertThatThrownBy(() -> userService.updateStaffDetails(2L, detailsWithRole(role), admin))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo(ErrorCodes.ROLE_NOT_ASSIGNABLE);
        }
        assertThat(operator.getRole()).isEqualTo(UserRole.OPERATOR);
        verify(userRepository, never()).save(any());
    }

    @Test
    void phoneNumberOfAnotherUserIsRefused() {
        User owner = user(1L, UserRole.OWNER, null);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(operator));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(userRepository.existsByPhoneNumberAndIdNot("9876500000", 2L)).thenReturn(true);
        UpdateStaffDetailsRequest request = new UpdateStaffDetailsRequest();
        request.setPhoneNumber("9876500000");

        assertThatThrownBy(() -> userService.updateStaffDetails(2L, request, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.PHONE_IN_USE_BY_ANOTHER_USER);
        assertThat(operator.getTokenVersion()).isZero();
    }

    @Test
    void newPhoneNumberEndsTheStaffMembersSessions() {
        User owner = user(1L, UserRole.OWNER, null);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        givenOwnerManages(operator);
        UpdateStaffDetailsRequest request = new UpdateStaffDetailsRequest();
        request.setPhoneNumber("9876500001");

        userService.updateStaffDetails(2L, request, owner);

        assertThat(operator.getPhoneNumber()).isEqualTo("9876500001");
        assertThat(operator.getTokenVersion()).isEqualTo(1);
    }

    @Test
    void ownerAccountCannotBeEditedAsStaff() {
        User owner = user(1L, UserRole.OWNER, null);
        User otherOwner = user(2L, UserRole.OWNER, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(otherOwner));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        UpdateStaffDetailsRequest request = new UpdateStaffDetailsRequest();
        request.setFullName("Renamed");

        assertThatThrownBy(() -> userService.updateStaffDetails(2L, request, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.OWNER_ACCOUNT_NOT_EDITABLE_HERE);
        assertThat(otherOwner.getFullName()).isEqualTo("OWNER 2");
    }

    @Test
    void nightShiftConsentIsKeptForAFemaleOperatorAndDroppedOnceSheIsNoLongerFemale() {
        User owner = user(1L, UserRole.OWNER, null);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        givenOwnerManages(operator);

        userService.updateStaffDetails(2L, detailsWithGenderAndConsent(UserGender.FEMALE), owner);
        assertThat(operator.isNightShiftConsent()).isTrue();

        userService.updateStaffDetails(2L, detailsWithGenderAndConsent(UserGender.MALE), owner);
        assertThat(operator.isNightShiftConsent()).isFalse();
    }

    @Test
    void staffListHidesPhoneNumbersAndPayRatesFromManagersButShowsThemToTheOwner() {
        User owner = user(1L, UserRole.OWNER, null);
        User manager = user(3L, UserRole.MANAGER, PUMP_ID);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        operator.setStandardHourlyRate(new BigDecimal("60"));
        operator.setDailyRate(new BigDecimal("900"));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(userRepository.findByAssignedPumpId(PUMP_ID)).thenReturn(List.of(operator, user(4L, UserRole.OWNER, PUMP_ID)));

        assertThat(userService.getStaff(PUMP_ID, owner)).singleElement()
                .satisfies(staff -> assertThat(staff.phoneNumber()).isEqualTo(operator.getPhoneNumber()));
        assertThat(userService.getStaff(PUMP_ID, manager)).singleElement().satisfies(staff -> {
            assertThat(staff.phoneNumber()).isNull();
            assertThat(staff.dailyRate()).isNull();
            assertThat(staff.standardHourlyRate()).isNull();
            assertThat(staff.shift1HourlyRate()).isNull();
        });
    }

    @Test
    void adminCannotCreateAnOwner() {
        User admin = user(1L, UserRole.ADMIN, PUMP_ID);
        CreateUserRequest request = new CreateUserRequest();
        request.setPhoneNumber("9876500002");
        request.setRole(UserRole.OWNER);

        assertThatThrownBy(() -> userService.createUser(request, admin))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.ROLE_NOT_ASSIGNABLE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void staffWithAnOpenShiftCannotBeMovedToAnotherPump() {
        User owner = user(1L, UserRole.OWNER, null);
        User operator = user(2L, UserRole.OPERATOR, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(operator));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(pumpLocationRepository.findById(9L)).thenReturn(Optional.of(pump(9L, 1L)));
        when(shiftRepository.hasOpenShift(2L)).thenReturn(true);

        assertThatThrownBy(() -> userService.moveToPump(2L, 9L, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.MOVE_STAFF_BLOCKED_BY_OPEN_SHIFT);
        assertThat(operator.getAssignedPumpId()).isEqualTo(PUMP_ID);
    }

    @Test
    void staffCannotBeMovedToAPumpTheOwnerDoesNotOwn() {
        User owner = user(1L, UserRole.OWNER, null);
        User accountant = user(2L, UserRole.ACCOUNTANT, PUMP_ID);
        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(accountant));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(pumpLocationRepository.findById(9L)).thenReturn(Optional.of(pump(9L, 50L)));

        assertThatThrownBy(() -> userService.moveToPump(2L, 9L, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.MOVE_STAFF_TARGET_PUMP_NOT_OWNED);
        assertThat(accountant.getAssignedPumpId()).isEqualTo(PUMP_ID);
    }

    @Test
    void movedStaffMemberWorksAtTheNewPumpAndTheirSessionsEnd() {
        User owner = user(1L, UserRole.OWNER, null);
        User manager = user(2L, UserRole.MANAGER, PUMP_ID);
        givenOwnerManages(manager);
        when(pumpLocationRepository.findById(9L)).thenReturn(Optional.of(pump(9L, 1L)));

        userService.moveToPump(2L, 9L, owner);

        assertThat(manager.getAssignedPumpId()).isEqualTo(9L);
        assertThat(manager.getTokenVersion()).isEqualTo(1);
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.USER_PUMP_CHANGED), eq("User"), eq("2"), anyString(), eq(owner));
        verify(auditService).log(eq(9L), eq(AuditAction.USER_PUMP_CHANGED), eq("User"), eq("2"), anyString(), eq(owner));
    }

    private void givenOwnerManages(User staff) {
        when(userRepository.findByIdForUpdate(staff.getId())).thenReturn(Optional.of(staff));
        when(pumpLocationRepository.existsByIdAndOwnerId(PUMP_ID, 1L)).thenReturn(true);
        when(userRepository.save(staff)).thenReturn(staff);
    }

    private static PumpLocation pump(long id, long ownerId) {
        return PumpLocation.builder().id(id).ownerId(ownerId).name("Pump " + id).build();
    }

    private static UpdateStaffDetailsRequest detailsWithRole(UserRole role) {
        UpdateStaffDetailsRequest request = new UpdateStaffDetailsRequest();
        request.setRole(role);
        return request;
    }

    private static UpdateStaffDetailsRequest detailsWithGenderAndConsent(UserGender gender) {
        UpdateStaffDetailsRequest request = new UpdateStaffDetailsRequest();
        request.setGender(gender);
        request.setNightShiftConsent(true);
        return request;
    }

    private static ResetPasswordRequest resetRequest() {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setTemporaryPassword(TEMPORARY_PASSWORD);
        return request;
    }

    private static User user(long id, UserRole role, Long pumpId) {
        return User.builder()
                .id(id)
                .fullName(role + " " + id)
                .phoneNumber("90000000" + String.format("%02d", id))
                .passwordHash("old-hash")
                .role(role)
                .status(UserStatus.ACTIVE)
                .assignedPumpId(pumpId)
                .build();
    }
}
