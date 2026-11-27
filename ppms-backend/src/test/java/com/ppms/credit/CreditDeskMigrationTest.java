package com.ppms.credit;

import com.ppms.support.PostgresTestContainerConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V56 on a database that already holds a client with an opening balance: existing clients stay open and not written
 * off, a client may now hold more than one opening balance, and the new tables refuse rows that break their rules.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditDeskMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existingClientsStayOpenAndTheNewTablesKeepTheirRules() {
        String database = "v56_credit_desk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "54");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V56', 'Owner', '8000000056', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = db.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long clientId = db.queryForObject(
                    "INSERT INTO credit_clients (pump_id, name, credit_limit) VALUES (?, 'Client', 0) RETURNING id",
                    Long.class, pumpId);
            openingBalance(db, pumpId, clientId, ownerId);

            migrate(dataSource, "56");

            assertThat(db.queryForObject("SELECT closed_at IS NULL AND written_off_on IS NULL AND merged_into_client_id"
                    + " IS NULL FROM credit_clients WHERE id = ?", Boolean.class, clientId)).isTrue();
            assertThat(db.queryForObject("SELECT group_invoice_by_default FROM pump_locations WHERE id = ?",
                    Boolean.class, pumpId)).isFalse();
            openingBalance(db, pumpId, clientId, ownerId);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO credit_adjustments (pump_id, client_id, kind, amount, adjustment_date, reason, created_by)
                    VALUES (?, ?, 'ADVANCE_REFUND', 100, CURRENT_DATE, 'No mode', ?)
                    """, pumpId, clientId, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
            dispute(db, pumpId, clientId, ownerId);
            assertThatThrownBy(() -> dispute(db, pumpId, clientId, ownerId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("CREDIT_NOTE_ISSUED", "CREDIT_WRITTEN_OFF", "CREDIT_CLIENTS_MERGED", "CREDIT_DISPUTE_RESOLVED");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static void openingBalance(JdbcTemplate db, Long pumpId, Long clientId, Long userId) {
        db.update("""
                INSERT INTO credit_opening_balances (pump_id, client_id, amount, as_of_date, created_by_user_id)
                VALUES (?, ?, 500, DATE '2026-04-01', ?)
                """, pumpId, clientId, userId);
    }

    private static void dispute(JdbcTemplate db, Long pumpId, Long clientId, Long userId) {
        db.update("""
                INSERT INTO credit_disputes (pump_id, client_id, debit_type, debit_id, debit_date, amount, reason, raised_by)
                VALUES (?, ?, 'SALE', 7, CURRENT_DATE, 100, 'Wrong bill', ?)
                """, pumpId, clientId, userId);
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .ignoreMigrationPatterns("*:missing")
                .target(target)
                .load()
                .migrate();
    }
}
