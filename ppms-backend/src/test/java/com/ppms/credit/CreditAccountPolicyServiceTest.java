package com.ppms.credit;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.FixedBusinessClock;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditAccountPolicyServiceTest {

    private static final long PUMP_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 6);

    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final CreditInterestService interestService = mock(CreditInterestService.class);
    private final CreditExtensionRepository extensionRepository = mock(CreditExtensionRepository.class);
    private final CreditOverdueService overdueService = mock(CreditOverdueService.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final CreditCloseExceptionRepository closeExceptionRepository = mock(CreditCloseExceptionRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    private final CreditAccountPolicyService policyService = new CreditAccountPolicyService(clientRepository,
            interestService, extensionRepository, overdueService, pumpLocationRepository,
            new FixedBusinessClock(OffsetDateTime.parse("2026-03-06T10:00:00+05:30")),
            mock(EntityManager.class), closeExceptionRepository, auditService);

    private final PumpLocation pump = PumpLocation.builder().id(PUMP_ID).build();

    @BeforeEach
    void givenPumpWithDefaultHoldDays() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));
    }

    @Test
    void clientIsFoundByIdOnlyWhenItBelongsToThePump() {
        CreditClient client = client(1L, PUMP_ID, null, true);
        CreditClient otherPumpClient = client(2L, PUMP_ID + 1, null, true);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(clientRepository.findById(2L)).thenReturn(Optional.of(otherPumpClient));

        assertThat(policyService.requireClientForCreditEntry(PUMP_ID, 1L, "ignored")).isSameAs(client);
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, 2L, "ignored"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void clientIsFoundByExactNameAndAnUnknownNameIsRefused() {
        CreditClient client = client(1L, PUMP_ID, null, true);
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Fleet Co")).thenReturn(List.of(client));
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Walk-in")).thenReturn(List.of());

        assertThat(policyService.requireClientForCreditEntry(PUMP_ID, null, "  Fleet Co ")).isSameAs(client);
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, "Walk-in"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No credit client named 'Walk-in'");
        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, " "))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nameMatchingTwoMainAccountsIsRefusedInsteadOfPickingOne() {
        when(clientRepository.findByPumpIdAndNameAndParentClientIdIsNull(PUMP_ID, "Fleet Co"))
                .thenReturn(List.of(client(1L, PUMP_ID, null, true), client(2L, PUMP_ID, null, true)));

        assertThatThrownBy(() -> policyService.requireClientForCreditEntry(PUMP_ID, null, "Fleet Co"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLIENT_NAME_AMBIGUOUS");
    }

    @Test
    void disabledClientOrSubAccountOfADisabledParentCannotTakeCredit() {
        CreditClient disabledParent = client(1L, PUMP_ID, null, false);
        CreditClient subAccount = client(2L, PUMP_ID, 1L, true);
        CreditClient activeStandalone = client(3L, PUMP_ID, null, true);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(disabledParent));

        assertThatThrownBy(() -> policyService.requireClientAcceptsCredit(disabledParent))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Credit is disabled");
        assertThatThrownBy(() -> policyService.requireClientAcceptsCredit(subAccount))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("parent account");
        assertThatNoException().isThrownBy(() -> policyService.requireClientAcceptsCredit(activeStandalone));
    }

    @Test
    void creditIsHeldFromTheHoldDaysOnUnlessAHoldOverrideIsInEffect() {
        CreditExtension waiver = extension(CreditExtensionType.OVERDUE_BLOCK_WAIVER);
        CreditExtension override = extension(CreditExtensionType.CREDIT_HOLD_OVERRIDE);

        assertThat(CreditAccountPolicyService.isOnHold(45, 44, List.of())).isFalse();
        assertThat(CreditAccountPolicyService.isOnHold(45, 45, List.of())).isTrue();
        assertThat(CreditAccountPolicyService.isOnHold(45, 90, List.of(waiver))).isTrue();
        assertThat(CreditAccountPolicyService.isOnHold(45, 90, List.of(waiver, override))).isFalse();
        assertThat(CreditAccountPolicyService.isOnHold(null, 400, List.of())).isFalse();
    }

    @Test
    void standingOfAnUnlimitedClientIsOkWithNoAvailableAmount() {
        CreditClient client = client(1L, PUMP_ID, null, true);
        client.setUnlimitedCredit(true);

        CreditClientStandingResponse standing = standingsOf(List.of(client), Map.of(1L, owing("900.00", 0))).get(1L);

        assertThat(standing.creditStatus()).isEqualTo(CreditStatus.OK);
        assertThat(standing.availableCredit()).isNull();
    }

    @Test
    void standingUnderTheLimitIsOkWithTheRemainingAmountAndAtTheLimitIsOverLimitWithNothingLeft() {
        CreditClient under = limited(1L, "1000.00");
        CreditClient reached = limited(2L, "500.00");

        Map<Long, CreditClientStandingResponse> standings = standingsOf(List.of(under, reached),
                Map.of(1L, owing("400.00", 0), 2L, owing("500.00", 0)));

        assertThat(standings.get(1L).creditStatus()).isEqualTo(CreditStatus.OK);
        assertThat(standings.get(1L).availableCredit()).isEqualByComparingTo("600.00");
        assertThat(standings.get(2L).creditStatus()).isEqualTo(CreditStatus.OVER_LIMIT);
        assertThat(standings.get(2L).availableCredit()).isEqualByComparingTo("0");
    }

    @Test
    void amountExtensionAddsToWhatIsAvailable() {
        CreditClient client = limited(1L, "500.00");
        givenExtensionsAtPump(amountExtension(1L, "1000.00"));

        CreditClientStandingResponse standing = standingsOf(List.of(client), Map.of(1L, owing("500.00", 0))).get(1L);

        assertThat(standing.creditStatus()).isEqualTo(CreditStatus.OK);
        assertThat(standing.availableCredit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void subAccountSharesItsParentsLimitAndStanding() {
        CreditClient parent = limited(1L, "1000.00");
        CreditClient child = client(2L, PUMP_ID, 1L, true);

        Map<Long, CreditClientStandingResponse> standings = standingsOf(List.of(parent, child),
                Map.of(1L, owing("800.00", 0)));

        assertThat(standings.get(1L).availableCredit()).isEqualByComparingTo("200.00");
        assertThat(standings.get(2L).availableCredit()).isEqualByComparingTo("200.00");
        assertThat(standings.get(2L).creditStatus()).isEqualTo(standings.get(1L).creditStatus());
    }

    @Test
    void disabledClientAndSubAccountOfADisabledParentAreInactive() {
        CreditClient disabledParent = client(1L, PUMP_ID, null, false);
        CreditClient child = client(2L, PUMP_ID, 1L, true);
        CreditClient disabledStandalone = client(3L, PUMP_ID, null, false);

        Map<Long, CreditClientStandingResponse> standings = standingsOf(
                List.of(disabledParent, child, disabledStandalone), Map.of());

        assertThat(standings.values()).extracting(CreditClientStandingResponse::creditStatus)
                .containsOnly(CreditStatus.INACTIVE);
    }

    @Test
    void groupWithAnOverdueBillIsOverdueUntilABillingCycleExtensionLiftsIt() {
        CreditClient parent = limited(1L, "5000.00");
        CreditClient child = client(2L, PUMP_ID, 1L, true);
        Map<Long, CreditBillLedger.Standing> overdue = Map.of(1L, owing("300.00", 10));

        Map<Long, CreditClientStandingResponse> before = standingsOf(List.of(parent, child), overdue);
        givenExtensionsAtPump(extension(1L, CreditExtensionType.BILLING_CYCLE_EXTENSION));
        Map<Long, CreditClientStandingResponse> after = standingsOf(List.of(parent, child), overdue);

        assertThat(before.get(1L).creditStatus()).isEqualTo(CreditStatus.OVERDUE);
        assertThat(before.get(2L).creditStatus()).isEqualTo(CreditStatus.OVERDUE);
        assertThat(after.get(1L).creditStatus()).isEqualTo(CreditStatus.OK);
    }

    @Test
    void groupPastTheHoldDaysIsOnHoldAndWithTheHoldTurnedOffOnlyOverdue() {
        CreditClient client = limited(1L, "5000.00");
        Map<Long, CreditBillLedger.Standing> fortyFiveDays = Map.of(1L, owing("300.00", 45));
        Map<Long, CreditBillLedger.Standing> fortyFourDays = Map.of(1L, owing("300.00", 44));

        CreditStatus held = standingsOf(List.of(client), fortyFiveDays).get(1L).creditStatus();
        CreditStatus belowHold = standingsOf(List.of(client), fortyFourDays).get(1L).creditStatus();
        pump.setCreditHoldDays(null);
        CreditStatus holdOff = standingsOf(List.of(client), Map.of(1L, owing("300.00", 90))).get(1L).creditStatus();

        assertThat(held).isEqualTo(CreditStatus.ON_HOLD);
        assertThat(belowHold).isEqualTo(CreditStatus.OVERDUE);
        assertThat(holdOff).isEqualTo(CreditStatus.OVERDUE);
    }

    @Test
    void groupOwingNinetyPercentOfItsLimitIsNearLimit() {
        CreditClient client = limited(1L, "1000.00");

        CreditClientStandingResponse standing = standingsOf(List.of(client), Map.of(1L, owing("900.00", 0))).get(1L);

        assertThat(standing.creditStatus()).isEqualTo(CreditStatus.NEAR_LIMIT);
        assertThat(standing.nearLimit()).isTrue();
    }

    @Test
    void creditSaleOfAGroupPastTheHoldDaysIsRefusedAsOnHold() {
        CreditClient parent = limited(1L, "5000.00");
        CreditClient child = client(2L, PUMP_ID, 1L, true);
        givenHolder(parent, "300.00", 45);

        assertThatThrownBy(() -> policyService.validateCreditSaleAllowed(PUMP_ID, child, new BigDecimal("100.00")))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_ON_HOLD");
                    assertThat(refusal.getParams()).containsEntry("daysOverdue", 45L).containsEntry("holdDays", 45);
                });
    }

    @Test
    void creditSaleBeyondTheGroupLimitIsRefused() {
        CreditClient client = limited(1L, "1000.00");
        givenHolder(client, "900.00", 0);

        assertThatThrownBy(() -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("100.01")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_LIMIT_EXCEEDED");
        assertThatNoException().isThrownBy(
                () -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("100.00")));
    }

    @Test
    void creditSaleOfAnOverdueGroupIsRefusedUnlessACycleWaiverOrHoldOverrideIsInEffect() {
        CreditClient client = limited(1L, "5000.00");
        givenHolder(client, "300.00", 50);
        pump.setCreditHoldDays(null);

        assertThatThrownBy(() -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("10.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_BILLING_CYCLE_OVERDUE");

        givenExtensionsOf(1L, extension(1L, CreditExtensionType.OVERDUE_BLOCK_WAIVER));
        assertThatNoException().isThrownBy(
                () -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("10.00")));
    }

    @Test
    void cycleWaiverDoesNotLiftTheHoldButAHoldOverrideDoes() {
        CreditClient client = limited(1L, "5000.00");
        givenHolder(client, "300.00", 50);

        givenExtensionsOf(1L, extension(1L, CreditExtensionType.OVERDUE_BLOCK_WAIVER));
        assertThatThrownBy(() -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("50.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_ON_HOLD");

        givenExtensionsOf(1L, extension(1L, CreditExtensionType.CREDIT_HOLD_OVERRIDE));
        assertThatNoException().isThrownBy(
                () -> policyService.validateCreditSaleAllowed(PUMP_ID, client, new BigDecimal("50.00")));
    }

    @Test
    void closeCheckNamesEveryBlockedAccountWithItsReasonInOneRefusal() {
        CreditClient overLimit = named(limited(1L, "1000.00"), "Over Limit Co");
        CreditClient onHold = named(limited(2L, "5000.00"), "Held Co");
        CreditClient overdue = named(limited(3L, "5000.00"), "Late Co");
        CreditClient fine = named(limited(4L, "5000.00"), "Fine Co");
        givenHolder(overLimit, "0.00", 0);
        givenHolder(onHold, "100.00", 50);
        givenHolder(overdue, "100.00", 10);
        givenHolder(fine, "0.00", 0);

        assertThatThrownBy(() -> policyService.validateCreditLimits(amounts(overLimit, "1500.00", onHold, "10.00",
                overdue, "10.00", fine, "10.00")))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("CREDIT_CLOSE_BLOCKED_CLIENTS");
                    assertThat(refusal.getParams()).containsEntry("overLimitClients", "Over Limit Co")
                            .containsEntry("onHoldClients", "Held Co")
                            .containsEntry("overdueClients", "Late Co");
                    assertThat(refusal.getMessage()).contains("'Held Co' is on hold", "against a limit of 1000.00")
                            .doesNotContain("Fine Co");
                });
    }

    @Test
    void closeCheckCountsAnAmountExtensionOnTopOfTheLimitAndAddsUpEntriesOfOneGroup() {
        CreditClient parent = limited(1L, "1000.00");
        CreditClient child = client(2L, PUMP_ID, 1L, true);
        givenHolder(parent, "0.00", 0);
        givenExtensionsOf(1L, amountExtension(1L, "1000.00"));

        assertThatNoException().isThrownBy(
                () -> policyService.validateCreditLimits(amounts(parent, "1000.00", child, "800.00")));
        assertThatThrownBy(() -> policyService.validateCreditLimits(amounts(parent, "1000.00", child, "1000.01")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void closeOverrideMustComeFromAnOwnerOrAdminWithAReason() {
        CreditClient client = limited(1L, "5000.00");
        givenHolder(client, "100.00", 10);

        assertThatThrownBy(() -> policyService.validateCreditLimits(amounts(client, "50.00"),
                new CloseCreditOverride(9L, "Paid by cheque tomorrow", user(UserRole.MANAGER))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLOSE_OVERRIDE_OWNER_ONLY");
        assertThatThrownBy(() -> policyService.validateCreditLimits(amounts(client, "50.00"),
                new CloseCreditOverride(9L, " ", user(UserRole.OWNER))))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CREDIT_CLOSE_OVERRIDE_REASON_REQUIRED");
        verify(closeExceptionRepository, never()).save(any());
    }

    @Test
    void ownerOverrideLetsBlockedCreditThroughAndRecordsAndAuditsEachException() {
        CreditClient client = limited(1L, "5000.00");
        givenHolder(client, "100.00", 10);
        when(closeExceptionRepository.save(any())).thenAnswer(call -> {
            CreditCloseException exception = call.getArgument(0);
            exception.setId(77L);
            return exception;
        });
        User owner = user(UserRole.OWNER);

        policyService.validateCreditLimits(amounts(client, "50.00"),
                new CloseCreditOverride(9L, " Paid by cheque tomorrow ", owner));

        ArgumentCaptor<CreditCloseException> saved = ArgumentCaptor.forClass(CreditCloseException.class);
        verify(closeExceptionRepository).save(saved.capture());
        assertThat(saved.getValue().getBlockedReason()).isEqualTo(CreditStatus.OVERDUE);
        assertThat(saved.getValue().getShiftId()).isEqualTo(9L);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("50.00");
        assertThat(saved.getValue().getAllowedByUserId()).isEqualTo(owner.getId());
        assertThat(saved.getValue().getReason()).isEqualTo("Paid by cheque tomorrow");
        verify(auditService).log(eq(PUMP_ID), eq(AuditAction.CREDIT_CLOSE_BLOCKED_ALLOWED), eq("CreditCloseException"),
                eq("77"), anyString(), eq(owner));
    }

    private Map<Long, CreditClientStandingResponse> standingsOf(List<CreditClient> clients,
                                                               Map<Long, CreditBillLedger.Standing> overdueByHolderId) {
        return policyService.creditStandingOfPumpClients(PUMP_ID, clients, overdueByHolderId);
    }

    private void givenHolder(CreditClient holder, String outstanding, long daysOverdue) {
        when(clientRepository.findById(holder.getId())).thenReturn(Optional.of(holder));
        when(clientRepository.findByIdForUpdate(holder.getId())).thenReturn(Optional.of(holder));
        when(overdueService.standingOfAccount(holder, TODAY)).thenReturn(owing(outstanding, daysOverdue));
        when(interestService.computeOutstandingWithChildren(holder.getId())).thenReturn(new BigDecimal(outstanding));
    }

    private void givenExtensionsAtPump(CreditExtension... extensions) {
        when(extensionRepository.findInEffectAtPump(PUMP_ID, TODAY)).thenReturn(List.of(extensions));
    }

    private void givenExtensionsOf(long holderId, CreditExtension... extensions) {
        when(extensionRepository.findInEffect(PUMP_ID, holderId, TODAY)).thenReturn(List.of(extensions));
    }

    private static Map<CreditClient, BigDecimal> amounts(Object... clientAndAmount) {
        Map<CreditClient, BigDecimal> amounts = new LinkedHashMap<>();
        for (int i = 0; i < clientAndAmount.length; i += 2) {
            amounts.put((CreditClient) clientAndAmount[i], new BigDecimal((String) clientAndAmount[i + 1]));
        }
        return amounts;
    }

    private static CreditBillLedger.Standing owing(String outstanding, long daysOverdue) {
        return new CreditBillLedger.Standing(new BigDecimal(outstanding), List.of(), null, daysOverdue,
                daysOverdue > 0 ? TODAY.minusDays(daysOverdue) : null);
    }

    private static CreditExtension extension(CreditExtensionType type) {
        return CreditExtension.builder().extensionType(type).build();
    }

    private static CreditExtension extension(long clientId, CreditExtensionType type) {
        return CreditExtension.builder().clientId(clientId).extensionType(type).build();
    }

    private static CreditExtension amountExtension(long clientId, String amount) {
        return CreditExtension.builder().clientId(clientId).extensionType(CreditExtensionType.AMOUNT_EXTENSION)
                .extensionAmount(new BigDecimal(amount)).build();
    }

    private static User user(UserRole role) {
        User user = new User();
        user.setId(role.ordinal() + 100L);
        user.setRole(role);
        return user;
    }

    private static CreditClient named(CreditClient client, String name) {
        client.setName(name);
        return client;
    }

    private static CreditClient limited(Long id, String creditLimit) {
        CreditClient client = client(id, PUMP_ID, null, true);
        client.setCreditLimit(new BigDecimal(creditLimit));
        return client;
    }

    private static CreditClient client(Long id, Long pumpId, Long parentClientId, boolean active) {
        return CreditClient.builder()
                .id(id)
                .pumpId(pumpId)
                .name("Client " + id)
                .parentClientId(parentClientId)
                .isActive(active)
                .build();
    }
}
