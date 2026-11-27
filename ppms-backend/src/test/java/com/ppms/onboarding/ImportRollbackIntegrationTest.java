package com.ppms.onboarding;

import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.support.ApiIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A failure while rows are being written, after every row passed its checks, leaves nothing of the file behind.
 * A test-only trigger makes the database refuse the second product's opening stock, without a separate Spring context.
 */
class ImportRollbackIntegrationTest extends ApiIntegrationTest {

    private static final String FAILING_PRODUCT = "Rollback Probe Second";

    @Autowired private AncillaryProductRepository productRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void dropTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS refuse_probe_lot ON ancillary_stock_lots");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS refuse_probe_lot()");
    }

    @Test
    void failureOnTheSecondProductRollsBackTheFirst() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        jdbcTemplate.execute("""
                CREATE FUNCTION refuse_probe_lot() RETURNS trigger AS $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM ancillary_products WHERE id = NEW.product_id AND name = '%s') THEN
                        RAISE EXCEPTION 'simulated failure while saving opening stock';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(FAILING_PRODUCT));
        jdbcTemplate.execute("CREATE TRIGGER refuse_probe_lot BEFORE INSERT ON ancillary_stock_lots "
                + "FOR EACH ROW EXECUTE FUNCTION refuse_probe_lot()");

        perform(post("/api/pumps/{pumpId}/imports/products", setup.pumpId()), setup.owner(), Map.of("rows", List.of(
                Map.of("rowNumber", 2, "name", "Rollback Probe First", "unit", "PIECE", "packageSize", "1",
                        "openingStock", "5", "openingCost", "10"),
                Map.of("rowNumber", 3, "name", FAILING_PRODUCT, "unit", "PIECE", "packageSize", "1",
                        "openingStock", "5", "openingCost", "10"))))
                .andExpect(status().is5xxServerError());

        assertThat(productRepository.findByPumpId(setup.pumpId())).isEmpty();
    }
}
