package com.ppms.collection;

import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The V44 schema keeps one open promise per account, UPI details whole, and the reminder stages in range. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditCollectionSchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private CreditClientRepository clientRepository;
    @Autowired private CreditPaymentPromiseRepository promiseRepository;

    private User owner;
    private PumpLocation pump;
    private CreditClient client;

    @BeforeEach
    void createPumpAndClient() {
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
        client = clientRepository.save(CreditClient.builder().pumpId(pump.getId()).name("Client " + unique).build());
    }

    @Test
    void newPumpStartsWithRemindersAtSevenFifteenAndThirtyDaysAndAHoldAtFortyFive() {
        PumpLocation saved = pumpLocationRepository.findById(pump.getId()).orElseThrow();

        assertThat(saved.getReminderStageDays()).containsExactly(7, 15, 30);
        assertThat(saved.getCreditHoldDays()).isEqualTo(45);
    }

    @Test
    void accountCanHaveOneOpenPromiseAndAnotherOnceItIsResolved() {
        CreditPaymentPromise first = promiseRepository.saveAndFlush(promise());

        assertThatThrownBy(() -> promiseRepository.saveAndFlush(promise()))
                .isInstanceOf(DataIntegrityViolationException.class);

        first.setStatus(PromiseStatus.MISSED);
        first.setPaidAmount(BigDecimal.ZERO);
        first.setResolvedAt(OffsetDateTime.now());
        promiseRepository.saveAndFlush(first);
        assertThat(promiseRepository.saveAndFlush(promise()).getId()).isNotNull();
    }

    @Test
    void resolvedPromiseMustSayWhenAndHowMuchWasPaid() {
        CreditPaymentPromise promise = promiseRepository.saveAndFlush(promise());
        promise.setStatus(PromiseStatus.KEPT);

        assertThatThrownBy(() -> promiseRepository.saveAndFlush(promise))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void upiIdWithoutAPayeeNameAndStagesOutOfRangeAreRefused() {
        PumpLocation withoutPayee = pumpLocationRepository.findById(pump.getId()).orElseThrow();
        withoutPayee.setUpiId("ramfuels@okaxis");
        assertThatThrownBy(() -> pumpLocationRepository.saveAndFlush(withoutPayee))
                .isInstanceOf(DataIntegrityViolationException.class);

        PumpLocation longStage = pumpLocationRepository.findById(pump.getId()).orElseThrow();
        longStage.setReminderStageDays(new Integer[]{7, 400});
        assertThatThrownBy(() -> pumpLocationRepository.saveAndFlush(longStage))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreditPaymentPromise promise() {
        return CreditPaymentPromise.builder()
                .pumpId(pump.getId())
                .clientId(client.getId())
                .amount(new BigDecimal("500.00"))
                .promisedDate(TestDates.today().plusDays(3))
                .recordedOn(TestDates.today())
                .recordedByUserId(owner.getId())
                .build();
    }
}
