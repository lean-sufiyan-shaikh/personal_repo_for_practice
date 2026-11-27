package com.ppms.credit;

import com.ppms.expense.ExpenseApprovalStatus;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.support.PostgresTestContainerConfig;
import com.ppms.support.TestDates;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import com.ppms.user.UserRole;
import com.ppms.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The V50 schema: a payment is reversed at most once, cheque payments and REVERSED are stored, settings stay in range. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditPaymentReversalSchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditPaymentRepository paymentRepository;
    @Autowired private CreditPaymentReversalRepository reversalRepository;

    private User owner;
    private PumpLocation pump;
    private CreditPayment payment;

    @BeforeEach
    void createReversedChequePayment() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName("Owner " + unique)
                .phoneNumber("8" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L))
                .passwordHash("hash")
                .role(UserRole.OWNER)
                .status(UserStatus.ACTIVE)
                .dateOfJoining(TestDates.today())
                .build());
        pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump " + unique)
                .address("Address")
                .maxDuCount(4)
                .build());
        CreditClient client = clientRepository.save(CreditClient.builder().pumpId(pump.getId()).name("Client " + unique).build());
        payment = paymentRepository.saveAndFlush(CreditPayment.builder()
                .pumpId(pump.getId())
                .clientId(client.getId())
                .amount(new BigDecimal("1000.00"))
                .paidAt(TestDates.today())
                .paymentMode(PaymentMode.CHEQUE)
                .referenceNo("000123")
                .chequeBank("SBI")
                .chequeDate(TestDates.today())
                .recordedById(owner.getId())
                .paymentApprovalStatus(ExpenseApprovalStatus.REVERSED)
                .build());
    }

    @Test
    void chequePaymentMarkedReversedIsStoredAsItWasSaved() {
        CreditPayment saved = paymentRepository.findById(payment.getId()).orElseThrow();

        assertThat(saved.getPaymentMode()).isEqualTo(PaymentMode.CHEQUE);
        assertThat(saved.getPaymentApprovalStatus()).isEqualTo(ExpenseApprovalStatus.REVERSED);
        assertThat(saved.getChequeBank()).isEqualTo("SBI");
    }

    @Test
    void paymentIsReversedAtMostOnce() {
        reversalRepository.saveAndFlush(reversal(null));

        assertThatThrownBy(() -> reversalRepository.saveAndFlush(reversal(null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bounceChargeMustBeAboveZeroAndTheReasonNotBlank() {
        assertThatThrownBy(() -> reversalRepository.saveAndFlush(reversal(BigDecimal.ZERO)))
                .isInstanceOf(DataIntegrityViolationException.class);

        CreditPaymentReversal blankReason = reversal(null);
        blankReason.setReason("  ");
        assertThatThrownBy(() -> reversalRepository.saveAndFlush(blankReason))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newPumpWaitsForApprovalOfPaymentsBackDatedOverSevenDaysAndBackDatedDaysStayInRange() {
        PumpLocation saved = pumpLocationRepository.findById(pump.getId()).orElseThrow();
        assertThat(saved.getBackdatedPaymentApprovalDays()).isEqualTo(7);
        assertThat(saved.getCreditPaymentApprovalThreshold()).isNull();

        saved.setBackdatedPaymentApprovalDays(366);
        assertThatThrownBy(() -> pumpLocationRepository.saveAndFlush(saved))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreditPaymentReversal reversal(BigDecimal bounceCharge) {
        return CreditPaymentReversal.builder()
                .pumpId(pump.getId())
                .clientId(payment.getClientId())
                .paymentId(payment.getId())
                .amount(payment.getAmount())
                .bounceCharge(bounceCharge)
                .reversalDate(TestDates.today())
                .reason("Cheque bounced")
                .reversedByUserId(owner.getId())
                .build();
    }
}
