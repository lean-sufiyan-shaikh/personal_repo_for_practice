package com.ppms.shift;

import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V53: pump defaults, one open shift per operator, cash recovery bounds and the new tables' checks. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShiftAccuracySchemaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PumpLocationRepository pumpLocationRepository;
    @Autowired private DispensaryUnitRepository duRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User owner;
    private User operator;
    private PumpLocation pump;
    private DispensaryUnit du;

    @BeforeEach
    void createPump() {
        owner = saveUser(UserRole.OWNER);
        operator = saveUser(UserRole.OPERATOR);
        pump = pumpLocationRepository.save(PumpLocation.builder()
                .ownerId(owner.getId())
                .name("Pump " + UUID.randomUUID().toString().substring(0, 8))
                .address("Address")
                .maxDuCount(4)
                .build());
        du = duRepository.save(DispensaryUnit.builder().pumpId(pump.getId()).duNumber(1).name("DU-1").build());
    }

    @Test
    void aPumpCreatedWithoutSettingsEscalatesAboveFiveHundredWithThirtyMinutesGraceAndOneRupeeRounding() {
        jdbcTemplate.update("INSERT INTO pump_locations (owner_id, name, address, max_du_count, created_at, updated_at) "
                + "VALUES (?, ?, 'Address', 2, NOW(), NOW())", owner.getId(), "Raw " + UUID.randomUUID());
        var defaults = jdbcTemplate.queryForMap("SELECT discrepancy_escalation_threshold, early_start_grace_minutes, "
                + "rounding_tolerance FROM pump_locations WHERE owner_id = ? AND name LIKE 'Raw %'", owner.getId());

        assertThat((BigDecimal) defaults.get("discrepancy_escalation_threshold")).isEqualByComparingTo("500.00");
        assertThat(defaults.get("early_start_grace_minutes")).isEqualTo(30);
        assertThat((BigDecimal) defaults.get("rounding_tolerance")).isEqualByComparingTo("1.00");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE pump_locations SET discrepancy_escalation_threshold = NULL WHERE id = ?", pump.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE pump_locations SET early_start_grace_minutes = 121 WHERE id = ?", pump.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOperatorHasAtMostOneOpenShiftButAutoClosedOnesDoNotCount() {
        shiftRepository.saveAndFlush(shift(ShiftStatus.AUTO_CLOSED_OVERDUE));
        shiftRepository.saveAndFlush(shift(ShiftStatus.OPEN));

        assertThatThrownBy(() -> shiftRepository.saveAndFlush(shift(ShiftStatus.OPEN_OVERDUE)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_shift_one_open_per_operator");
    }

    @Test
    void cashRecoveredCannotExceedTheDiscrepancyAndNeedsItsDate() {
        Shift shift = shift(ShiftStatus.CLOSED_DISCREPANCY_PENDING);
        shift.setDiscrepancyAmount(new BigDecimal("100.00"));
        shift.setDiscrepancyType(DiscrepancyType.SHORT);
        Shift saved = shiftRepository.saveAndFlush(shift);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE shifts SET cash_recovery_amount = 150, cash_recovered_at = NOW() WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE shifts SET cash_recovery_amount = 50 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("UPDATE shifts SET cash_recovery_amount = 50, cash_recovered_at = NOW() WHERE id = ?", saved.getId());
    }

    @Test
    void investigationNotesNeedTextAndPriceSegmentsAPositivePrice() {
        Shift saved = shiftRepository.saveAndFlush(shift(ShiftStatus.CLOSED_DISCREPANCY_PENDING));

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO shift_investigation_notes (shift_id, note, written_by_user_id) "
                + "VALUES (?, '  ', ?)", saved.getId(), owner.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO shift_reading_price_segments "
                + "(shift_fuel_reading_id, start_reading, price_per_unit, starts_at) VALUES (-1, 10, 0, NOW())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Shift shift(ShiftStatus status) {
        return Shift.builder()
                .pumpId(pump.getId())
                .duId(du.getId())
                .operatorId(operator.getId())
                .openedByUserId(owner.getId())
                .shiftDate(TestDates.today())
                .actualStartTime(TestDates.now())
                .status(status)
                .build();
    }

    private User saveUser(UserRole role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .employeeId("EMP-" + UUID.randomUUID())
                .fullName(role + " " + unique)
                .phoneNumber("7" + String.format("%09d", Math.abs(System.nanoTime()) % 1_000_000_000L))
                .passwordHash("hash")
                .role(role)
                .status(UserStatus.ACTIVE)
                .dateOfJoining(TestDates.today())
                .build());
    }
}
