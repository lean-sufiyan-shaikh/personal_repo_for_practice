package com.ppms.cash;

import com.ppms.support.PostgresTestContainerConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V43 on a database that already holds safe counts: each gets the business day (India time) it was counted on. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SafeCountBusinessDateMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existingCountsGetTheBusinessDayTheyWereCountedOnAndAreNotLate() {
        String database = "v43_backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "42");
            JdbcTemplate testDatabase = new JdbcTemplate(dataSource);
            Long ownerId = testDatabase.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V43', 'Owner', '8000000043', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = testDatabase.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            for (String countedAt : List.of("2026-03-09T20:00:00Z", "2026-03-10T10:00:00Z", "2026-03-10T18:29:00Z")) {
                testDatabase.update("""
                        INSERT INTO safe_counts (pump_id, counted_amount, expected_amount, variance_amount, tolerance_amount,
                                                 counted_by_user_id, counted_at)
                        VALUES (?, 0, 0, 0, 10, ?, ?::timestamptz)
                        """, pumpId, ownerId, countedAt);
            }

            migrate(dataSource, "43");

            List<Map<String, Object>> counts = testDatabase.queryForList(
                    "SELECT count_for_date, late, back_date_reason FROM safe_counts WHERE pump_id = ?", pumpId);
            assertThat(counts).hasSize(3).allSatisfy(count -> {
                assertThat(((Date) count.get("count_for_date")).toLocalDate()).isEqualTo(LocalDate.of(2026, 3, 10));
                assertThat(count.get("late")).isEqualTo(false);
                assertThat(count.get("back_date_reason")).isNull();
            });
            assertThat(testDatabase.queryForObject("SELECT safe_count_cutoff_time::text FROM pump_locations WHERE id = ?",
                    String.class, pumpId)).isEqualTo("10:00:00");
            assertThat(testDatabase.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("SAFE_COUNTED_LATE", "SAFE_COUNT_BACK_DATED");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
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
