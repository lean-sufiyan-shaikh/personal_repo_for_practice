package com.ppms.settlement;

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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V49 on a database that already holds settlements, settlement configs and statement lines. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SettlementChargesMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existingSettlementsKeepTheirAmountAsGrossAndNetWithNoChargesAndZeroOnesAreVoided() {
        String database = "v49_backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "44");
            JdbcTemplate testDatabase = new JdbcTemplate(dataSource);
            Long ownerId = testDatabase.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V49', 'Owner', '8000000049', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = testDatabase.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long settledId = insertSettlement(testDatabase, pumpId, ownerId, "750.00");
            Long zeroId = insertSettlement(testDatabase, pumpId, ownerId, "0.00");
            testDatabase.update("INSERT INTO payment_settlement_configs (pump_id, payment_type) VALUES (?, 'CARD'::settlement_payment_type)", pumpId);
            testDatabase.update("INSERT INTO payment_settlement_configs (pump_id, payment_type) VALUES (?, 'UPI'::settlement_payment_type)", pumpId);

            migrate(dataSource, "49");

            Map<String, Object> settled = testDatabase.queryForMap(
                    "SELECT amount_received, mdr_amount, gst_on_mdr_amount, net_amount, void_status FROM payment_settlements WHERE id = ?", settledId);
            assertThat((BigDecimal) settled.get("net_amount")).isEqualByComparingTo("750.00");
            assertThat((BigDecimal) settled.get("amount_received")).isEqualByComparingTo("750.00");
            assertThat((BigDecimal) settled.get("mdr_amount")).isEqualByComparingTo("0");
            assertThat((BigDecimal) settled.get("gst_on_mdr_amount")).isEqualByComparingTo("0");
            assertThat(settled.get("void_status")).isEqualTo("ACTIVE");
            assertThat(testDatabase.queryForObject("SELECT void_status FROM payment_settlements WHERE id = ?", String.class, zeroId))
                    .isEqualTo("VOIDED");

            assertThat(testDatabase.queryForObject(
                    "SELECT mdr_percent FROM payment_settlement_configs WHERE pump_id = ? AND payment_type = 'CARD'", BigDecimal.class, pumpId))
                    .isEqualByComparingTo("1.8");
            assertThat(testDatabase.queryForObject(
                    "SELECT mdr_percent FROM payment_settlement_configs WHERE pump_id = ? AND payment_type = 'UPI'", BigDecimal.class, pumpId))
                    .isEqualByComparingTo("0");
            assertThat(testDatabase.queryForObject(
                    "SELECT gst_on_mdr_percent FROM payment_settlement_configs WHERE pump_id = ? AND payment_type = 'UPI'", BigDecimal.class, pumpId))
                    .isEqualByComparingTo("18");

            assertThatThrownBy(() -> testDatabase.update("""
                    INSERT INTO payment_settlements (pump_id, payment_type, settlement_date, amount_received, net_amount, recorded_by_user_id)
                    VALUES (?, 'UPI'::settlement_payment_type, DATE '2026-03-11', 0, 0, ?)
                    """, pumpId, ownerId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_ps_amount_positive");
            assertThat(testDatabase.queryForList("SELECT unnest(enum_range(NULL::bank_line_match_status))::text", String.class))
                    .contains("SUGGESTED");
            assertThat(testDatabase.queryForList("SELECT unnest(enum_range(NULL::notification_type))::text", String.class))
                    .contains("SETTLEMENT_OVERDUE");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Long insertSettlement(JdbcTemplate database, Long pumpId, Long userId, String amount) {
        return database.queryForObject("""
                INSERT INTO payment_settlements (pump_id, payment_type, settlement_date, amount_received, recorded_by_user_id)
                VALUES (?, 'UPI'::settlement_payment_type, DATE '2026-03-10', ?, ?) RETURNING id
                """, Long.class, pumpId, new BigDecimal(amount), userId);
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
