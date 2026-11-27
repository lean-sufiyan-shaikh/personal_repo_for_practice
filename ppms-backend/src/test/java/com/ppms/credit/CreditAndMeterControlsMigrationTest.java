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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V52 on a database that already holds credit clients and extensions: clients whose limit of 0 meant "no limit" keep
 * unlimited credit, duplicate active extensions are expired keeping the latest, and new duplicates are refused.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreditAndMeterControlsMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void zeroLimitClientsKeepUnlimitedCreditAndOnlyTheLatestActiveExtensionOfATypeSurvives() {
        String database = "v52_backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "49");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V52', 'Owner', '8000000052', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = db.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long noLimitClient = client(db, pumpId, "No limit", "0");
            Long limitedClient = client(db, pumpId, "Limited", "5000");
            Long olderExtension = extension(db, pumpId, limitedClient, ownerId, "AMOUNT_EXTENSION", "2026-01-01T10:00:00Z");
            Long latestExtension = extension(db, pumpId, limitedClient, ownerId, "AMOUNT_EXTENSION", "2026-02-01T10:00:00Z");
            Long otherType = extension(db, pumpId, limitedClient, ownerId, "OVERDUE_BLOCK_WAIVER", "2026-01-15T10:00:00Z");

            migrate(dataSource, "52");

            assertThat(unlimited(db, noLimitClient)).isTrue();
            assertThat(unlimited(db, limitedClient)).isFalse();
            assertThat(status(db, olderExtension)).isEqualTo("EXPIRED");
            assertThat(status(db, latestExtension)).isEqualTo("ACTIVE");
            assertThat(status(db, otherType)).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> extension(db, pumpId, limitedClient, ownerId, "AMOUNT_EXTENSION", "2026-03-01T10:00:00Z"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Map<String, Object> settings = db.queryForMap(
                    "SELECT default_credit_limit, manager_credit_limit_cap, near_limit_percent FROM pump_locations WHERE id = ?",
                    pumpId);
            assertThat(settings.get("default_credit_limit").toString()).isEqualTo("0.00");
            assertThat(settings.get("manager_credit_limit_cap").toString()).isEqualTo("50000.00");
            assertThat(settings.get("near_limit_percent")).isEqualTo(90);
            assertThat(db.queryForObject("SELECT portal_pin_weak FROM credit_clients WHERE id = ?", Boolean.class, noLimitClient))
                    .isNull();
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("CREDIT_CLIENT_UPDATED", "CREDIT_PORTAL_LOCKED", "CREDIT_CLOSE_BLOCKED_ALLOWED",
                            "METER_ADJUSTMENT_APPROVED");
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::notification_type))::text", String.class))
                    .contains("CREDIT_PORTAL_LOCKED", "CREDIT_CLIENT_PHONE_CHANGED");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Long client(JdbcTemplate db, Long pumpId, String name, String limit) {
        return db.queryForObject("INSERT INTO credit_clients (pump_id, name, credit_limit) VALUES (?, ?, ?::numeric) RETURNING id",
                Long.class, pumpId, name, limit);
    }

    private static Long extension(JdbcTemplate db, Long pumpId, Long clientId, Long userId, String type, String createdAt) {
        return db.queryForObject("""
                INSERT INTO credit_extensions (pump_id, client_id, extension_type, extension_amount, expiry_date, reason,
                                               granted_by_user_id, created_at)
                VALUES (?, ?, ?::credit_extension_type, 1000, DATE '2030-01-01', 'Test', ?, ?::timestamptz) RETURNING id
                """, Long.class, pumpId, clientId, type, userId, createdAt);
    }

    private static boolean unlimited(JdbcTemplate db, Long clientId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT unlimited_credit FROM credit_clients WHERE id = ?",
                Boolean.class, clientId));
    }

    private static String status(JdbcTemplate db, Long extensionId) {
        return db.queryForObject("SELECT status::text FROM credit_extensions WHERE id = ?", String.class, extensionId);
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
