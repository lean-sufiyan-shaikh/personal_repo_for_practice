package com.ppms.shift;

import com.ppms.support.PostgresTestContainerConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V58 on a database at V57 that already holds a pump and an open shift: the pump counts blind by default, and the new
 * shift operation and staff advance tables refuse rows that break their rules.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShiftOperationsMigrationTest {

    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong();

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String database;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate db;

    @BeforeEach
    void createDatabase() {
        database = "v58_shift_operations_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        dataSource = new SingleConnectionDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                        + "/" + database,
                postgres.getUsername(), postgres.getPassword(), true);
        db = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void dropDatabase() {
        dataSource.destroy();
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
    }

    @Test
    void existingPumpCountsBlindAndNewTablesGuardTheirRows() {
        migrate("57");
        Long ownerId = user("OWNER");
        Long operatorId = user("OPERATOR");
        Long pumpId = db.queryForObject("INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                Long.class, ownerId);
        Long duId = db.queryForObject("INSERT INTO dispensary_units (pump_id, du_number, name) VALUES (?, 1, 'DU-1') RETURNING id",
                Long.class, pumpId);
        Long nozzleId = db.queryForObject("""
                INSERT INTO nozzles (du_id, nozzle_number, fuel_type, last_reading) VALUES (?, 1, 'PETROL', 1000) RETURNING id
                """, Long.class, duId);
        Long shiftId = db.queryForObject("""
                INSERT INTO shifts (pump_id, du_id, operator_id, opened_by_user_id, shift_date, actual_start_time, status)
                VALUES (?, ?, ?, ?, DATE '2026-11-01', NOW(), 'OPEN') RETURNING id
                """, Long.class, pumpId, duId, operatorId, ownerId);

        migrate("58");

        assertThat(db.queryForObject("SELECT blind_cash_count FROM pump_locations WHERE id = ?", Boolean.class, pumpId)).isTrue();
        assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                .contains("BLIND_CASH_COUNTED", "SHIFT_OPERATOR_ADDED", "SHIFT_OPERATOR_REMOVED", "NOZZLE_SWAPPED",
                        "RELIEF_STARTED", "RELIEF_ENDED", "STAFF_ADVANCE_GIVEN", "STAFF_ADVANCE_VOIDED");
        assertThatThrownBy(() -> db.update("UPDATE shifts SET blind_cash_counted = 100 WHERE id = ?", shiftId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long second = user("OPERATOR");
        db.update("INSERT INTO shift_operators (shift_id, operator_id, role, joined_at, added_by_user_id) VALUES (?, ?, 'ADDITIONAL', NOW(), ?)",
                shiftId, second, ownerId);
        assertThatThrownBy(() -> db.update("""
                INSERT INTO shift_operators (shift_id, operator_id, role, joined_at, added_by_user_id)
                VALUES (?, ?, 'RELIEF', NOW(), ?)
                """, shiftId, second, ownerId)).isInstanceOf(DataIntegrityViolationException.class);

        db.update("""
                INSERT INTO shift_nozzle_turns (shift_id, nozzle_id, operator_id, start_reading, started_at, start_reason, recorded_by_user_id)
                VALUES (?, ?, ?, 1000, NOW(), 'SHIFT_OPEN', ?)
                """, shiftId, nozzleId, operatorId, ownerId);
        assertThatThrownBy(() -> db.update("""
                INSERT INTO shift_nozzle_turns (shift_id, nozzle_id, operator_id, start_reading, started_at, start_reason, recorded_by_user_id)
                VALUES (?, ?, ?, 1005, NOW(), 'SWAP', ?)
                """, shiftId, nozzleId, second, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("""
                INSERT INTO shift_reliefs (shift_id, relieved_operator_id, relief_operator_id, started_at, started_by_user_id)
                VALUES (?, ?, ?, NOW(), ?)
                """, shiftId, operatorId, operatorId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("""
                INSERT INTO shift_operator_totals (shift_id, operator_id, litres_sold, amount_due, cash_collected)
                VALUES (?, ?, 10, 1000, 1000)
                """, shiftId, operatorId)).isInstanceOf(DataIntegrityViolationException.class);

        Long cashEventId = db.queryForObject("""
                INSERT INTO cash_events (pump_id, event_type, amount, description, event_date, recorded_by_user_id)
                VALUES (?, 'CASH_OUT', 500, 'Advance', DATE '2026-11-01', ?) RETURNING id
                """, Long.class, pumpId, ownerId);
        Long advanceId = db.queryForObject("""
                INSERT INTO staff_advances (pump_id, staff_user_id, amount, advance_date, cash_event_id, given_by_user_id)
                VALUES (?, ?, 500, DATE '2026-11-01', ?, ?) RETURNING id
                """, Long.class, pumpId, operatorId, cashEventId, ownerId);
        assertThatThrownBy(() -> db.update("UPDATE staff_advances SET status = 'VOIDED' WHERE id = ?", advanceId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long payrollId = db.queryForObject("""
                INSERT INTO payroll_records (salary_type, pump_id, user_id, period_from, period_to, total_shifts, shift1_shifts,
                                             shift1_hours, standard_shifts, standard_hours, gross_amount, status)
                VALUES ('HOURLY_SHIFT', ?, ?, DATE '2026-11-01', DATE '2026-11-07', 0, 0, 0, 0, 0, 800, 'DRAFT') RETURNING id
                """, Long.class, pumpId, operatorId);
        assertThat(db.queryForObject("SELECT advance_recovered FROM payroll_records WHERE id = ?", Integer.class, payrollId)).isZero();
        db.update("INSERT INTO staff_advance_recoveries (advance_id, payroll_id, amount) VALUES (?, ?, 300)", advanceId, payrollId);
        db.update("DELETE FROM payroll_records WHERE id = ?", payrollId);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM staff_advance_recoveries WHERE advance_id = ?", Integer.class, advanceId))
                .isZero();
    }

    private Long user(String role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        return db.queryForObject("""
                INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                VALUES (?, ?, ?, 'hash', ?::user_role, 'ACTIVE', DATE '2026-01-01') RETURNING id
                """, Long.class, "EMP-" + unique, role + " " + unique, Long.toString(8_000_000_000L + PHONE_SEQUENCE.incrementAndGet()), role);
    }

    private void migrate(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .ignoreMigrationPatterns("*:missing")
                .target(target)
                .load()
                .migrate();
    }
}
