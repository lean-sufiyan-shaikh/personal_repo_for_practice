package com.ppms.dayclose;

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
 * V48 on a database that already holds safe movements and supplier payments: shift collections are linked to their
 * shift, hand-typed movements are marked, and older duplicates survive while the database refuses duplicates among
 * new rows (the services refuse every duplicate, old or new).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LockAndCorrectMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void existingRowsAreLinkedAndMarkedAndOnlyNewDuplicatesAreRefused() {
        String database = "v48_backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "44");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V48', 'Owner', '8000000048', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long pumpId = db.queryForObject(
                    "INSERT INTO pump_locations (owner_id, name, address) VALUES (?, 'Pump', 'Road') RETURNING id",
                    Long.class, ownerId);
            Long duId = db.queryForObject(
                    "INSERT INTO dispensary_units (pump_id, du_number, name) VALUES (?, 1, 'DU-1') RETURNING id", Long.class, pumpId);
            Long shiftId = db.queryForObject("""
                    INSERT INTO shifts (pump_id, du_id, operator_id, opened_by_user_id, shift_date, actual_start_time, status)
                    VALUES (?, ?, ?, ?, DATE '2026-03-10', TIMESTAMPTZ '2026-03-10T08:00:00Z', 'CLOSED_BALANCED') RETURNING id
                    """, Long.class, pumpId, duId, ownerId, ownerId);
            Long collection = cashEvent(db, pumpId, ownerId, "CASH_IN", "Cash collection from Nozzles #1 shift close (Shift #" + shiftId + ")");
            Long recovery = cashEvent(db, pumpId, ownerId, "CASH_IN", "Cash recovered from operator for the shortage on Shift #" + shiftId);
            Long counterSale = cashEvent(db, pumpId, ownerId, "CASH_IN", "Counter sale: Oil × 1 units (Sale #4)");
            Long typedIn = cashEvent(db, pumpId, ownerId, "CASH_IN", "Change from the bank");
            Long opening = cashEvent(db, pumpId, ownerId, "OPENING_BALANCE", "Float");
            cashEvent(db, pumpId, ownerId, "OPENING_BALANCE", "Float typed twice");
            Long supplierId = db.queryForObject(
                    "INSERT INTO fuel_suppliers (pump_id, name) VALUES (?, 'IOCL') RETURNING id", Long.class, pumpId);
            supplierPayment(db, pumpId, supplierId, ownerId, "UTR-1");
            supplierPayment(db, pumpId, supplierId, ownerId, "utr-1 ");

            migrate(dataSource, "48");

            Map<String, Object> linked = db.queryForMap("SELECT shift_id, recorded_by_hand FROM cash_events WHERE id = ?", collection);
            assertThat(((Number) linked.get("shift_id")).longValue()).isEqualTo(shiftId);
            assertThat(linked.get("recorded_by_hand")).isEqualTo(false);
            assertThat(recordedByHand(db, recovery)).isFalse();
            assertThat(recordedByHand(db, counterSale)).isFalse();
            assertThat(recordedByHand(db, typedIn)).isTrue();
            assertThat(recordedByHand(db, opening)).isTrue();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM fuel_supplier_payments WHERE void_status = 'ACTIVE'", Long.class))
                    .isEqualTo(2L);

            cashEvent(db, pumpId, ownerId, "OPENING_BALANCE", "First float after the upgrade");
            assertThatThrownBy(() -> cashEvent(db, pumpId, ownerId, "OPENING_BALANCE", "Second float after the upgrade"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            supplierPayment(db, pumpId, supplierId, ownerId, "Utr-1");
            assertThatThrownBy(() -> supplierPayment(db, pumpId, supplierId, ownerId, " UTR-1"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE fuel_supplier_payments SET void_status = 'VOIDED' WHERE supplier_id = ?", supplierId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("SHIFT_AMENDED", "CASH_EVENT_RECORDED", "CASH_EVENT_REVERSED", "SUPPLIER_PAYMENT_VOIDED");
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Long cashEvent(JdbcTemplate db, Long pumpId, Long userId, String type, String description) {
        return db.queryForObject("""
                INSERT INTO cash_events (pump_id, event_type, amount, description, event_date, recorded_by_user_id)
                VALUES (?, ?::cash_event_type, 100, ?, DATE '2026-03-10', ?) RETURNING id
                """, Long.class, pumpId, type, description, userId);
    }

    private static void supplierPayment(JdbcTemplate db, Long pumpId, Long supplierId, Long userId, String reference) {
        db.update("""
                INSERT INTO fuel_supplier_payments (pump_id, supplier_id, amount, payment_date, payment_mode, reference_no,
                                                    recorded_by_user_id)
                VALUES (?, ?, 1000, DATE '2026-03-10', 'BANK_TRANSFER', ?, ?)
                """, pumpId, supplierId, reference, userId);
    }

    private static boolean recordedByHand(JdbcTemplate db, Long eventId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT recorded_by_hand FROM cash_events WHERE id = ?", Boolean.class, eventId));
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
