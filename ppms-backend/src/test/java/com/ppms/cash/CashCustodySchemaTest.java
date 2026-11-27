package com.ppms.cash;

import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.DiscrepancyType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V41: the database itself keeps drops, counts and deposits consistent, and the new enum values exist. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CashCustodySchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private CashEventRepository cashEventRepository;
    @Autowired private SafeCountRepository safeCountRepository;
    @Autowired private BankDepositRepository depositRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private PumpLocation pump;

    @BeforeEach
    void createPump() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        owner = userRepository.save(User.builder()
                .employeeId("EMP-" + unique)
                .fullName("Owner " + unique)
                .phoneNumber("7" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L))
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
    }

    @Test
    void newPumpGetsATenRupeeSafeToleranceAndANegativeOneIsRefused() {
        PumpLocation saved = pumpLocationRepository.findById(pump.getId()).orElseThrow();
        assertThat(saved.getSafeCountTolerance()).isEqualByComparingTo("10.00");

        saved.setSafeCountTolerance(new BigDecimal("-1"));
        assertThatThrownBy(() -> pumpLocationRepository.saveAndFlush(saved)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newEnumValuesExist() {
        assertThat(jdbcTemplate.queryForList("SELECT unnest(enum_range(NULL::cash_event_type))::text", String.class))
                .contains("CASH_DROP", "BANK_DEPOSIT");
        assertThat(jdbcTemplate.queryForList("SELECT unnest(enum_range(NULL::notification_type))::text", String.class))
                .contains("CASH_DROP_PENDING", "SAFE_COUNT_VARIANCE");
        assertThat(jdbcTemplate.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                .contains("CASH_DROP_RECORDED", "CASH_DROP_CONFIRMED", "CASH_DROP_REJECTED", "SAFE_COUNTED",
                        "BANK_DEPOSIT_RECORDED", "DEPOSIT_SLIP_ATTACHED");
    }

    @Test
    void countWhoseVarianceDoesNotMatchItsFiguresIsRefused() {
        SafeCount count = SafeCount.builder()
                .pumpId(pump.getId())
                .countedAmount(new BigDecimal("90.00"))
                .expectedAmount(new BigDecimal("100.00"))
                .varianceAmount(new BigDecimal("-5.00"))
                .varianceType(DiscrepancyType.SHORT)
                .toleranceAmount(BigDecimal.TEN)
                .countedByUserId(owner.getId())
                .countedAt(TestDates.now())
                .countForDate(TestDates.today())
                .build();
        assertThatThrownBy(() -> safeCountRepository.saveAndFlush(count)).isInstanceOf(DataIntegrityViolationException.class);

        count.setVarianceAmount(new BigDecimal("-10.00"));
        count.setVarianceType(DiscrepancyType.OVER);
        assertThatThrownBy(() -> safeCountRepository.saveAndFlush(count)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void slipReferenceIsUniquePerPump() {
        depositRepository.saveAndFlush(deposit("SLIP-9"));

        assertThatThrownBy(() -> depositRepository.saveAndFlush(deposit("SLIP-9")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private BankDeposit deposit(String slipReference) {
        CashEvent event = cashEventRepository.saveAndFlush(CashEvent.builder()
                .pumpId(pump.getId())
                .eventType(CashEventType.BANK_DEPOSIT)
                .amount(new BigDecimal("100.00"))
                .description("Deposit")
                .eventDate(TestDates.today())
                .recordedByUserId(owner.getId())
                .build());
        return BankDeposit.builder()
                .pumpId(pump.getId())
                .amount(new BigDecimal("100.00"))
                .bankAccount("SBI")
                .depositDate(TestDates.today())
                .slipReference(slipReference)
                .cashEventId(event.getId())
                .recordedByUserId(owner.getId())
                .build();
    }
}
