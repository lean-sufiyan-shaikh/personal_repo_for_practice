package com.ppms.monthclose;

import com.ppms.support.PostgresTestContainerConfig;
import org.flywaydb.core.Flyway;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V57 on a database that already holds expenses and matched bank lines: older expenses keep no source and no cash
 * movement, a line still links to one record at most (chargebacks included), and the new tables hold their rules.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MoneyOperationsMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existingRecordsAreCarriedOverAndTheNewRulesHold() {
        String database = "v57_money_operations_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                        + "/" + database,
                postgres.getUsername(), postgres.getPassword(), true);
        try {
            migrate(dataSource, "56");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V57', 'Owner', '8000000057', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = db.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long expenseId = db.queryForObject("""
                    INSERT INTO pump_expenses (pump_id, category, amount, description, expense_date, recorded_by_user_id,
                                               approval_status, created_at)
                    VALUES (?, 'MAINTENANCE', 250, 'Hose', DATE '2026-03-04', ?, 'APPROVED', NOW()) RETURNING id
                    """, Long.class, pumpId, ownerId);
            Long settlementId = db.queryForObject("""
                    INSERT INTO payment_settlements (pump_id, payment_type, settlement_date, amount_received,
                                                     net_amount, recorded_by_user_id)
                    VALUES (?, 'CARD', DATE '2026-03-04', 600, 600, ?) RETURNING id
                    """, Long.class, pumpId, ownerId);
            Long importId = db.queryForObject("""
                    INSERT INTO bank_statement_imports (pump_id, bank_name, statement_from_date, statement_to_date,
                                                        imported_by_user_id)
                    VALUES (?, 'SBI', DATE '2026-03-01', DATE '2026-03-31', ?) RETURNING id
                    """, Long.class, pumpId, ownerId);
            Long lineId = db.queryForObject("""
                    INSERT INTO bank_statement_lines (import_id, txn_date, credit_amount, match_status, matched_settlement_id)
                    VALUES (?, DATE '2026-03-05', 600, 'MATCHED', ?) RETURNING id
                    """, Long.class, importId, settlementId);

            migrate(dataSource, "57");

            assertThat(db.queryForMap("SELECT paid_from, cash_event_id, voided_at FROM pump_expenses WHERE id = ?", expenseId))
                    .containsEntry("paid_from", null).containsEntry("cash_event_id", null).containsEntry("voided_at", null);
            assertThat(db.queryForObject("SELECT matched_settlement_id FROM bank_statement_lines WHERE id = ?", Long.class,
                    lineId)).isEqualTo(settlementId);

            Long chargebackId = db.queryForObject("""
                    INSERT INTO settlement_chargebacks (pump_id, kind, payment_type, chargeback_date, amount, reference,
                                                        reason, settlement_id, recorded_by_user_id)
                    VALUES (?, 'CHARGEBACK', 'CARD', DATE '2026-03-10', 100, 'CB-1', 'Disputed', ?, ?) RETURNING id
                    """, Long.class, pumpId, settlementId, ownerId);
            assertThatThrownBy(() -> db.update("UPDATE bank_statement_lines SET matched_chargeback_id = ? WHERE id = ?",
                    chargebackId, lineId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO settlement_chargebacks (pump_id, kind, payment_type, chargeback_date, amount, reference,
                                                        reason, collection_date, recorded_by_user_id)
                    VALUES (?, 'REFUND', 'UPI', DATE '2026-03-10', 5, ' cb-1 ', 'Twice', DATE '2026-03-09', ?)
                    """, pumpId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO settlement_chargebacks (pump_id, kind, payment_type, chargeback_date, amount, reason,
                                                        recorded_by_user_id)
                    VALUES (?, 'REFUND', 'UPI', DATE '2026-03-10', 5, 'No link', ?)
                    """, pumpId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);

            assertThatThrownBy(() -> db.update("""
                    INSERT INTO petty_cash_entries (pump_id, entry_type, amount, entry_date, description, expense_id,
                                                    recorded_by_user_id)
                    VALUES (?, 'EXPENSE', 40, DATE '2026-03-04', 'Positive draw', ?, ?)
                    """, pumpId, expenseId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO petty_cash_entries (pump_id, entry_type, amount, entry_date, description, recorded_by_user_id)
                    VALUES (?, 'TOP_UP', 40, DATE '2026-03-04', 'Top-up without the safe', ?)
                    """, pumpId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO month_closes (pump_id, month_start, status, current_version)
                    VALUES (?, DATE '2026-03-02', 'CLOSED', 1)
                    """, pumpId)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE pump_expenses SET voided_at = NOW() WHERE id = ?", expenseId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::expense_approval_status))::text", String.class))
                    .contains("VOIDED");
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::notification_type))::text", String.class))
                    .contains("PETTY_CASH_COUNT_VARIANCE");
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("EXPENSE_VOIDED", "PETTY_CASH_TOPPED_UP", "PETTY_CASH_COUNTED", "PETTY_CASH_SETTINGS_UPDATED",
                            "CHARGEBACK_RECORDED", "MONTH_CLOSED", "MONTH_REOPENED");
        } finally {
            dataSource.destroy();
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
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
