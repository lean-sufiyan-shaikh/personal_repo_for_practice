package com.ppms.payroll;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V54 on a database that already holds waived interest, paid payroll and salary deductions: waivers keep letting their
 * period be charged again, paid payroll gets its paid date, and a deduction a payroll already took is linked to it
 * while one chosen after that payroll was generated is left for the next payroll.
 */
class InterestCollectionsPayrollMigrationTest {

    @Test
    void existingWaiversPayrollAndDeductionsAreCarriedOver() {
        // A server of its own: the database server shared by the Spring test contexts runs close to its client limit.
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))) {
            postgres.start();
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
            migrate(dataSource, "50");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V54', 'Owner', '8000000054', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = db.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long duId = db.queryForObject(
                    "INSERT INTO dispensary_units (pump_id, du_number, name) VALUES (?, 1, 'DU-1') RETURNING id", Long.class, pumpId);
            Long clientId = db.queryForObject(
                    "INSERT INTO credit_clients (pump_id, name) VALUES (?, 'Fleet') RETURNING id", Long.class, pumpId);
            Long waivedCharge = charge(db, pumpId, clientId, ownerId, true);
            Long liveCharge = charge(db, pumpId, clientId, null, false);
            Long paidPayroll = db.queryForObject("""
                    INSERT INTO payroll_records (pump_id, user_id, period_from, period_to, status, created_at, updated_at)
                    VALUES (?, ?, DATE '2026-03-01', DATE '2026-03-31', 'PAID', TIMESTAMPTZ '2026-04-01T04:00:00Z',
                            TIMESTAMPTZ '2026-04-02T20:00:00Z') RETURNING id
                    """, Long.class, pumpId, ownerId);
            Long takenShift = deductionShift(db, pumpId, duId, ownerId, "2026-03-10", "2026-03-20T10:00:00Z");
            Long lateShift = deductionShift(db, pumpId, duId, ownerId, "2026-03-12", "2026-04-05T10:00:00Z");

            migrate(dataSource, "54");

            assertThat(db.queryForObject("SELECT waiver_kind FROM credit_interest_charges WHERE id = ?", String.class,
                    waivedCharge)).isEqualTo("RECALCULATE");
            assertThat(db.queryForObject("SELECT waiver_kind FROM credit_interest_charges WHERE id = ?", String.class,
                    liveCharge)).isNull();
            // Updated at 01:30 on 3 April in India.
            assertThat(db.queryForObject("SELECT paid_date FROM payroll_records WHERE id = ?", LocalDate.class, paidPayroll))
                    .isEqualTo(LocalDate.of(2026, 4, 3));
            assertThat(db.queryForList("SELECT shift_id FROM payroll_resolved_shifts WHERE payroll_id = ?", Long.class,
                    paidPayroll)).containsExactly(takenShift);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM payroll_resolved_shifts WHERE shift_id = ?", Long.class,
                    lateShift)).isZero();

            assertThatThrownBy(() -> db.update("UPDATE credit_interest_charges SET waiver_kind = 'KEEP_PERIOD_CHARGED' "
                    + "WHERE id = ?", liveCharge)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE payroll_records SET status = 'DRAFT' WHERE id = ?", paidPayroll))
                    .isInstanceOf(DataIntegrityViolationException.class);
            Long promiseId = db.queryForObject("""
                    INSERT INTO credit_payment_promises (pump_id, client_id, amount, promised_date, recorded_on,
                                                         recorded_by_user_id)
                    VALUES (?, ?, 100, DATE '2026-04-10', DATE '2026-04-01', ?) RETURNING id
                    """, Long.class, pumpId, clientId, ownerId);
            db.update("UPDATE credit_payment_promises SET status = 'CANCELLED', paid_amount = 0, resolved_at = NOW() "
                    + "WHERE id = ?", promiseId);
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("CREDIT_PROMISE_EDITED", "CREDIT_PROMISE_CANCELLED", "PAYROLL_PAID", "PAYROLL_RETURNED_TO_DRAFT");
            dataSource.destroy();
        }
    }

    private static Long charge(JdbcTemplate db, Long pumpId, Long clientId, Long waivedBy, boolean waived) {
        return db.queryForObject("""
                INSERT INTO credit_interest_charges (pump_id, client_id, amount, outstanding_balance, rate_applied,
                                                     days_applied, period_from, period_to, source, voided_at, voided_by,
                                                     void_reason)
                VALUES (?, ?, 10, 1000, 3, 31, DATE '2026-03-01', DATE '2026-03-31', 'MANUAL', ?, ?, ?) RETURNING id
                """, Long.class, pumpId, clientId,
                waived ? java.sql.Timestamp.valueOf("2026-04-02 10:00:00") : null, waivedBy, waived ? "Goodwill" : null);
    }

    private static Long deductionShift(JdbcTemplate db, Long pumpId, Long duId, Long operatorId, String shiftDate,
                                       String resolvedAt) {
        return db.queryForObject("""
                INSERT INTO shifts (pump_id, du_id, operator_id, opened_by_user_id, shift_date, actual_start_time, status,
                                    discrepancy_type, discrepancy_amount, discrepancy_resolution, discrepancy_resolved_at)
                VALUES (?, ?, ?, ?, ?::date, ?::date + TIME '08:00', 'CLOSED_DISCREPANCY_RESOLVED', 'SHORT', 50,
                        'SALARY_DEDUCTION', ?::timestamptz) RETURNING id
                """, Long.class, pumpId, duId, operatorId, operatorId, shiftDate, shiftDate, resolvedAt);
    }

    private static void migrate(SingleConnectionDataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .ignoreMigrationPatterns("*:missing")
                .target(target)
                .load()
                .migrate();
    }
}
