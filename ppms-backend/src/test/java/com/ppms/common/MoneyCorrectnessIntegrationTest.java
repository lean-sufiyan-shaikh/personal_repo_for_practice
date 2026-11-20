package com.ppms.common;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductPrice;
import com.ppms.ancillary.AncillaryProductPriceRepository;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillaryProductStatus;
import com.ppms.ancillary.RecordAncillarySaleRequest;
import com.ppms.ancillary.RecordStockDeliveryRequest;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import com.ppms.credit.CreditInterestCharge;
import com.ppms.credit.CreditInterestChargeRepository;
import com.ppms.credit.CreditInterestRunService;
import com.ppms.credit.CreditInterestService;
import com.ppms.credit.CreditLedgerQueryService;
import com.ppms.credit.CreditLedgerService;
import com.ppms.credit.CreditPayment;
import com.ppms.credit.RecordPaymentRequest;
import com.ppms.credit.CreditPaymentRepository;
import com.ppms.credit.CreditTransactionResponse;
import com.ppms.credit.PaymentMode;
import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.payroll.GeneratePayrollRequest;
import com.ppms.payroll.PayrollRecord;
import com.ppms.payroll.PayrollRepository;
import com.ppms.payroll.PayrollService;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.settlement.PaymentSettlementService;
import com.ppms.shift.DiscrepancyType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.shift.ShiftCreditEntryRepository;
import com.ppms.shift.ShiftRepository;
import com.ppms.shift.ShiftStatus;
import com.ppms.support.PostgresTestContainerConfig;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class MoneyCorrectnessIntegrationTest {

    @Autowired private CreditLedgerQueryService ledgerQueryService;
    @Autowired private CreditLedgerService ledgerService;
    @Autowired private CreditInterestService interestService;
    @Autowired private CreditInterestRunService interestRunService;
    @Autowired private PayrollService payrollService;
    @Autowired private PaymentSettlementService settlementService;
    @Autowired private AncillaryInventoryWorkflowService ancillaryWorkflowService;
    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private DispensaryUnitRepository duRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftCreditEntryRepository creditEntryRepository;
    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditPaymentRepository paymentRepository;
    @Autowired private CreditInterestChargeRepository interestChargeRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private AncillaryProductRepository ancillaryProductRepository;
    @Autowired private AncillaryProductPriceRepository ancillaryPriceRepository;

    private User owner;
    private User manager;
    private User operator;
    private PumpLocation pump;
    private DispensaryUnit du;

    @BeforeEach
    void createPump() {
        owner = saveUser(UserRole.OWNER, null);
        pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump " + shortId())
                .address("Test address")
                .maxDuCount(4)
                .build());
        manager = saveUser(UserRole.MANAGER, pump.getId());
        operator = saveUser(UserRole.OPERATOR, pump.getId());
        operator.setShift1HourlyRate(new BigDecimal("100.00"));
        operator.setStandardHourlyRate(new BigDecimal("80.00"));
        operator = userRepository.save(operator);
        du = duRepository.save(DispensaryUnit.builder()
                .pumpId(pump.getId())
                .duNumber(1)
                .name("DU-" + shortId())
                .build());
    }

    @Test
    void ledgerRunningBalanceIgnoresVoidedSalesAndUnapprovedPayments() {
        CreditClient client = saveClient(BigDecimal.ZERO);
        Shift shift = saveClosedShift(LocalDate.now(), ShiftStatus.CLOSED_BALANCED, null);
        saveCreditSale(shift, client, "1000.00", "ACTIVE");
        saveCreditSale(shift, client, "5000.00", "VOIDED");
        savePayment(client, "300.00", ExpenseApprovalStatus.APPROVED, owner);
        savePayment(client, "2000.00", ExpenseApprovalStatus.REJECTED, manager);
        savePayment(client, "100.00", ExpenseApprovalStatus.PENDING_APPROVAL, manager);

        List<CreditTransactionResponse> statement = ledgerQueryService.getStatementTransactions(client.getId(), null, null);

        assertThat(statement).hasSize(5);
        assertThat(statement.get(statement.size() - 1).getRunningBalance())
                .isEqualByComparingTo(interestService.computeOutstanding(client.getId()))
                .isEqualByComparingTo("700.00");
        assertThat(statement).filteredOn(tx -> !tx.isCountedInBalance())
                .extracting(CreditTransactionResponse::getStatus)
                .containsExactlyInAnyOrder("VOIDED", "REJECTED", "PENDING_APPROVAL");
    }

    @Test
    void payrollThatFailsAfterResolvingDiscrepanciesLeavesThemPending() {
        operator.setShift1HourlyRate(null);
        userRepository.save(operator);
        Shift discrepancy = saveClosedShift(LocalDate.now().minusDays(2), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "50.00");

        assertThatThrownBy(() -> payrollService.generate(pump.getId(), payrollRequest(discrepancy), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hourly rates");

        Shift reloaded = shiftRepository.findById(discrepancy.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(reloaded.getDiscrepancyResolution()).isNull();
    }

    @Test
    void secondPayrollForTheSamePeriodIsRefusedWithoutResolvingMoreDiscrepancies() {
        payrollService.generate(pump.getId(), payrollRequest(null), owner);
        Shift discrepancy = saveClosedShift(LocalDate.now().minusDays(2), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "50.00");

        assertThatThrownBy(() -> payrollService.generate(pump.getId(), payrollRequest(discrepancy), owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already covers");

        assertThat(shiftRepository.findById(discrepancy.getId()).orElseThrow().getStatus())
                .isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
    }

    @Test
    void payrollPeriodThatOverlapsAnEarlierOneIsRefused() {
        payrollService.generate(pump.getId(), payrollRequest(null), owner);

        GeneratePayrollRequest overlapping = payrollRequest(null);
        overlapping.setPeriodFrom(LocalDate.now().minusDays(2));
        overlapping.setPeriodTo(LocalDate.now().plusDays(5));

        assertThatThrownBy(() -> payrollService.generate(pump.getId(), overlapping, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("overlaps");
    }

    @Test
    void discrepancyDeductedByOnePayrollIsNotDeductedAgainByAnother() {
        Shift lastMonth = saveClosedShift(LocalDate.now().minusDays(20), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "500.00");
        PayrollRecord current = payrollService.generate(pump.getId(), payrollRequest(lastMonth), owner);
        assertThat(current.getDeductions()).isEqualByComparingTo("500.00");

        GeneratePayrollRequest earlier = payrollRequest(null);
        earlier.setPeriodFrom(LocalDate.now().minusDays(30));
        earlier.setPeriodTo(LocalDate.now().minusDays(8));
        PayrollRecord earlierPayroll = payrollService.generate(pump.getId(), earlier, owner);

        assertThat(earlierPayroll.getDeductions()).isEqualByComparingTo("0.00");
    }

    @Test
    void subAccountPaymentCannotTakeTheGroupBelowZero() {
        CreditClient parent = saveClient(BigDecimal.ZERO);
        CreditClient child = clientRepository.save(CreditClient.builder()
                .pumpId(pump.getId())
                .name("Child " + shortId())
                .parentClientId(parent.getId())
                .monthlyInterestRate(BigDecimal.ZERO)
                .build());
        Shift shift = saveClosedShift(LocalDate.now(), ShiftStatus.CLOSED_BALANCED, null);
        saveCreditSale(shift, child, "1000.00", "ACTIVE");
        savePayment(parent, "1000.00", ExpenseApprovalStatus.APPROVED, owner);

        RecordPaymentRequest request = new RecordPaymentRequest();
        request.setAmount(new BigDecimal("1000.00"));
        request.setPaymentMode(PaymentMode.CASH);
        request.setPaidAt(LocalDate.now());

        assertThatThrownBy(() -> ledgerService.recordPayment(pump.getId(), child.getId(), request, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds the amount still payable");
    }

    @Test
    void deletingPayrollDraftRestoresTheDiscrepanciesItResolved() {
        Shift discrepancy = saveClosedShift(LocalDate.now().minusDays(2), ShiftStatus.CLOSED_DISCREPANCY_PENDING, "50.00");
        PayrollRecord draft = payrollService.generate(pump.getId(), payrollRequest(discrepancy), owner);
        assertThat(draft.getDeductions()).isEqualByComparingTo("50.00");
        assertThat(shiftRepository.findById(discrepancy.getId()).orElseThrow().getStatus())
                .isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_RESOLVED);

        payrollService.deleteDraft(pump.getId(), draft.getId(), owner);

        Shift restored = shiftRepository.findById(discrepancy.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        assertThat(restored.getDiscrepancyResolution()).isNull();
        assertThat(payrollRepository.findById(draft.getId())).isEmpty();
    }

    @Test
    void payrollPaysWorkedMinutesAtTheHourlyRateRoundedOnce() {
        Shift shift = saveClosedShift(LocalDate.now().minusDays(1), ShiftStatus.CLOSED_BALANCED, null);
        shift.setActualStartTime(OffsetDateTime.now().minusDays(1).withHour(8).withMinute(0));
        shift.setActualEndTime(shift.getActualStartTime().plusMinutes(100));
        shiftRepository.save(shift);

        PayrollRecord draft = payrollService.generate(pump.getId(), payrollRequest(null), owner);

        // 100 min × ₹80/h = ₹133.333… → ₹133.33 (rounding hours first would give 1.67 h × 80 = ₹133.60)
        assertThat(draft.getGrossAmount()).isEqualByComparingTo("133.33");
    }

    @Test
    void interestRunKeepsOtherClientsWhenOneGroupFails() {
        CreditClient healthyRoot = saveClient(new BigDecimal("2.00"));
        seedOutstandingInterest(healthyRoot, "1000.00", 40);

        CreditClient failingRoot = saveClient(new BigDecimal("2.00"));
        seedOutstandingInterest(failingRoot, "1000.00", 40);
        CreditClient overflowingChild = saveClient(new BigDecimal("999.99"));
        overflowingChild.setParentClientId(failingRoot.getId());
        clientRepository.save(overflowingChild);
        seedOutstandingInterest(overflowingChild, "900000000000.00", 400);

        CreditInterestRunService.InterestRunResult result =
                interestRunService.applyProRataForAllClients(pump.getId(), owner.getId());

        assertThat(result.groupsCharged()).isEqualTo(1);
        assertThat(result.failedClientIds()).containsExactly(failingRoot.getId());
        assertThat(interestChargeRepository.findByClientIdOrderByCreatedAtDesc(healthyRoot.getId())).hasSize(2);
        assertThat(interestChargeRepository.findByClientIdOrderByCreatedAtDesc(failingRoot.getId())).hasSize(1);
    }

    @Test
    void nobodyCanApproveAPaymentTheyRecordedThemselves() {
        CreditClient client = saveClient(BigDecimal.ZERO);
        Shift shift = saveClosedShift(LocalDate.now(), ShiftStatus.CLOSED_BALANCED, null);
        saveCreditSale(shift, client, "1000.00", "ACTIVE");
        CreditPayment payment = savePayment(client, "500.00", ExpenseApprovalStatus.PENDING_APPROVAL, owner);

        assertThatThrownBy(() -> ledgerService.approvePayment(pump.getId(), payment.getId(), ExpenseApprovalStatus.APPROVED, owner))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("another Owner or Admin");
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getPaymentApprovalStatus())
                .isEqualTo(ExpenseApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    void voidedSettlementNoLongerReducesTheWalletAndDuplicatesAreRefused() {
        PaymentSettlementService.RecordSettlementRequest request = PaymentSettlementService.RecordSettlementRequest.builder()
                .paymentType("UPI")
                .settlementDate(LocalDate.now().toString())
                .amountReceived(new BigDecimal("79000.00"))
                .build();
        PaymentSettlementService.SettlementResponse recorded = settlementService.record(pump.getId(), request, manager);
        assertThat(settlementService.getWalletSummary(pump.getId()).getUpiPending()).isEqualByComparingTo("-79000.00");

        assertThatThrownBy(() -> settlementService.record(pump.getId(), request, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last 2 minutes");

        PaymentSettlementService.SettlementResponse voided = settlementService.voidSettlement(pump.getId(), recorded.getId(),
                PaymentSettlementService.VoidSettlementRequest.builder().reason("Typed 79000 instead of 7900").build(), owner);

        assertThat(voided.getVoidStatus()).isEqualTo("VOIDED");
        assertThat(settlementService.getWalletSummary(pump.getId()).getUpiPending()).isEqualByComparingTo("0.00");
    }

    @Test
    void managerCannotSellBelowTheConfiguredPriceButOwnerCan() {
        AncillaryProduct product = ancillaryProductRepository.save(AncillaryProduct.builder()
                .pumpId(pump.getId())
                .name("Engine oil " + shortId())
                .packageSize(new BigDecimal("1.000"))
                .unitOfMeasure(UnitOfMeasure.LITRE)
                .currentStockUnits(0)
                .gstRatePercent(new BigDecimal("18.00"))
                .status(AncillaryProductStatus.ACTIVE)
                .build());
        RecordStockDeliveryRequest stock = new RecordStockDeliveryRequest();
        stock.setQuantityUnits(10);
        stock.setCostPricePerUnit(new BigDecimal("300.00"));
        stock.setDeliveryDate(LocalDate.now());
        ancillaryWorkflowService.recordDelivery(pump.getId(), product.getId(), stock, manager);
        ancillaryPriceRepository.save(AncillaryProductPrice.builder()
                .productId(product.getId())
                .pumpId(pump.getId())
                .pricePerUnit(new BigDecimal("400.00"))
                .effectiveFrom(OffsetDateTime.now().minusDays(1))
                .setByUserId(owner.getId())
                .build());

        RecordAncillarySaleRequest cheapSale = new RecordAncillarySaleRequest();
        cheapSale.setProductId(product.getId());
        cheapSale.setQuantityUnits(1);
        cheapSale.setSellingPricePerUnit(new BigDecimal("0.01"));
        cheapSale.setPaymentMode(TransactionPaymentMode.UPI);

        assertThatThrownBy(() -> ancillaryWorkflowService.recordSale(pump.getId(), cheapSale, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only an Owner or Admin");
        assertThat(ancillaryWorkflowService.recordSale(pump.getId(), cheapSale, owner).getSale().getSellingPricePerUnit())
                .isEqualByComparingTo("0.01");
    }

    private GeneratePayrollRequest payrollRequest(Shift discrepancyToDeduct) {
        GeneratePayrollRequest request = new GeneratePayrollRequest();
        request.setUserId(operator.getId());
        request.setPeriodFrom(LocalDate.now().minusDays(7));
        request.setPeriodTo(LocalDate.now());
        request.setDeductFromSalaryShiftIds(discrepancyToDeduct == null ? null : List.of(discrepancyToDeduct.getId()));
        return request;
    }

    private Shift saveClosedShift(LocalDate shiftDate, ShiftStatus status, String discrepancyAmount) {
        OffsetDateTime start = shiftDate.atTime(9, 0).atOffset(OffsetDateTime.now().getOffset());
        return shiftRepository.save(Shift.builder()
                .pumpId(pump.getId())
                .duId(du.getId())
                .operatorId(operator.getId())
                .openedByUserId(manager.getId())
                .shiftDate(shiftDate)
                .actualStartTime(start)
                .actualEndTime(start.plusHours(8))
                .status(status)
                .discrepancyAmount(discrepancyAmount == null ? null : new BigDecimal(discrepancyAmount))
                .discrepancyType(discrepancyAmount == null ? null : DiscrepancyType.SHORT)
                .build());
    }

    private CreditClient saveClient(BigDecimal monthlyInterestRate) {
        return clientRepository.save(CreditClient.builder()
                .pumpId(pump.getId())
                .name("Client " + shortId())
                .monthlyInterestRate(monthlyInterestRate)
                .build());
    }

    private void saveCreditSale(Shift shift, CreditClient client, String amount, String voidStatus) {
        creditEntryRepository.save(ShiftCreditEntry.builder()
                .shiftId(shift.getId())
                .clientId(client.getId())
                .clientName(client.getName())
                .fuelType(FuelType.PETROL.name())
                .amount(new BigDecimal(amount))
                .voidStatus(voidStatus)
                .build());
    }

    private CreditPayment savePayment(CreditClient client, String amount, ExpenseApprovalStatus status, User recordedBy) {
        return paymentRepository.save(CreditPayment.builder()
                .pumpId(pump.getId())
                .clientId(client.getId())
                .amount(new BigDecimal(amount))
                .paymentMode(PaymentMode.CASH)
                .paidAt(LocalDate.now())
                .recordedById(recordedBy.getId())
                .paymentApprovalStatus(status)
                .build());
    }

    /** An earlier charge gives the client an outstanding balance and fixes where the next interest period starts. */
    private void seedOutstandingInterest(CreditClient client, String amount, int daysAgo) {
        LocalDate periodTo = LocalDate.now().minusDays(daysAgo);
        interestChargeRepository.save(CreditInterestCharge.builder()
                .pumpId(pump.getId())
                .clientId(client.getId())
                .amount(new BigDecimal(amount))
                .outstandingBalance(BigDecimal.ZERO)
                .rateApplied(BigDecimal.ONE)
                .daysApplied(1)
                .periodFrom(periodTo)
                .periodTo(periodTo)
                .source("TEST_SEED")
                .build());
    }

    private User saveUser(UserRole role, Long assignedPumpId) {
        String unique = shortId();
        return userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName(role + " " + unique)
                .phoneNumber(Long.toString(6_000_000_000L + (long) (Math.random() * 3_999_999_999L)))
                .passwordHash("not-used")
                .role(role)
                .status(UserStatus.ACTIVE)
                .assignedPumpId(assignedPumpId)
                .dateOfJoining(LocalDate.now())
                .build());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
