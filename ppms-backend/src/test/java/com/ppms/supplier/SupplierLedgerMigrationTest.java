package com.ppms.supplier;

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
 * V55 on a database that already holds suppliers and deliveries: a pump with one supplier gets it as its default and on
 * its older deliveries, a pump with several keeps them unassigned, and the new GST and test-litre columns are guarded.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SupplierLedgerMigrationTest {

    @Autowired private PostgreSQLContainer<?> postgres;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void singleSupplierIsBackfilledOntoDeliveriesAndBecomesTheDefault() {
        String database = "v55_backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                            + "/" + database,
                    postgres.getUsername(), postgres.getPassword());
            migrate(dataSource, "53");
            JdbcTemplate db = new JdbcTemplate(dataSource);
            Long ownerId = db.queryForObject("""
                    INSERT INTO users (employee_id, full_name, phone_number, password_hash, role, status, date_of_joining)
                    VALUES ('EMP-V55', 'Owner', '8000000055', 'hash', 'OWNER', 'ACTIVE', DATE '2026-01-01') RETURNING id
                    """, Long.class);
            Long singlePump = pump(db, ownerId);
            Long onlySupplier = supplier(db, singlePump, "IOCL");
            Long singlePumpDelivery = delivery(db, singlePump, ownerId, "INV-1");
            Long severalPump = pump(db, ownerId);
            Long first = supplier(db, severalPump, "IOCL");
            supplier(db, severalPump, "BPCL");
            Long severalPumpDelivery = delivery(db, severalPump, ownerId, "INV-2");

            migrate(dataSource, "55");

            assertThat(supplierOf(db, singlePumpDelivery)).isEqualTo(onlySupplier);
            assertThat(supplierOf(db, severalPumpDelivery)).isNull();
            assertThat(isDefault(db, onlySupplier)).isTrue();
            assertThat(isDefault(db, first)).isFalse();
            assertThatThrownBy(() -> db.update("UPDATE fuel_suppliers SET is_default = TRUE WHERE pump_id = ?", severalPump))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE fuel_suppliers SET opening_balance = 100 WHERE id = ?", first))
                    .isInstanceOf(DataIntegrityViolationException.class);

            Map<String, Object> settings = db.queryForMap(
                    "SELECT test_volume_litres, max_test_litres_per_nozzle FROM pump_locations WHERE id = ?", singlePump);
            assertThat(settings.get("test_volume_litres").toString()).isEqualTo("5.00");
            assertThat(settings.get("max_test_litres_per_nozzle").toString()).isEqualTo("10.00");
            assertThat(db.queryForObject("""
                    SELECT column_default FROM information_schema.columns
                    WHERE table_name = 'shift_fuel_readings' AND column_name = 'test_litres'
                    """, String.class)).isEqualTo("0");
            assertThat(db.queryForList("SELECT unnest(enum_range(NULL::audit_action))::text", String.class))
                    .contains("TEST_LITRES_ABOVE_MAX", "SUPPLIER_OPENING_BALANCE_SET", "SUPPLIER_DEFAULT_SET");

            Long productId = db.queryForObject("""
                    INSERT INTO ancillary_products (pump_id, name, unit_of_measure, package_size)
                    VALUES (?, 'Oil', 'LITRE', 1) RETURNING id
                    """, Long.class, singlePump);
            assertThatThrownBy(() -> db.update("""
                    INSERT INTO ancillary_stock_deliveries (product_id, pump_id, quantity_units, cost_price_per_unit,
                                                            delivery_date, logged_by_user_id, gst_supplier_name)
                    VALUES (?, ?, 1, 10, DATE '2026-11-01', ?, 'Half filled')
                    """, productId, singlePump, ownerId)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Long pump(JdbcTemplate db, Long ownerId) {
        return db.queryForObject("INSERT INTO pump_locations (owner_id, name, address) VALUES (?, ?, 'Road') RETURNING id",
                Long.class, ownerId, "Pump " + UUID.randomUUID());
    }

    private static Long supplier(JdbcTemplate db, Long pumpId, String name) {
        return db.queryForObject("INSERT INTO fuel_suppliers (pump_id, name) VALUES (?, ?) RETURNING id",
                Long.class, pumpId, name);
    }

    private static Long delivery(JdbcTemplate db, Long pumpId, Long userId, String invoice) {
        Long tankId = db.queryForObject("""
                INSERT INTO underground_tanks (pump_id, tank_identifier, fuel_type, capacity)
                VALUES (?, 'T1', 'PETROL', 20000) RETURNING id
                """, Long.class, pumpId);
        return db.queryForObject("""
                INSERT INTO tanker_deliveries (pump_id, tank_id, fuel_type, quantity_delivered, cost_price_per_unit,
                                               delivery_date, invoice_reference, logged_by_user_id)
                VALUES (?, ?, 'PETROL', 1000, 90, NOW(), ?, ?) RETURNING id
                """, Long.class, pumpId, tankId, invoice, userId);
    }

    private static Long supplierOf(JdbcTemplate db, Long deliveryId) {
        return db.queryForObject("SELECT supplier_id FROM tanker_deliveries WHERE id = ?", Long.class, deliveryId);
    }

    private static boolean isDefault(JdbcTemplate db, Long supplierId) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT is_default FROM fuel_suppliers WHERE id = ?",
                Boolean.class, supplierId));
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
