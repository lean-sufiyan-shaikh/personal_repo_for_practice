package com.ppms.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductPriceRepository;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillaryStockLot;
import com.ppms.ancillary.AncillaryStockLotRepository;
import com.ppms.fuel.FuelType;
import com.ppms.inventory.InventoryLot;
import com.ppms.inventory.InventoryLotRepository;
import com.ppms.pump.TankStatus;
import com.ppms.pump.UndergroundTank;
import com.ppms.support.ApiIntegrationTest;
import com.ppms.support.TestDates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.ppms.onboarding.StaffImportIntegrationTest.outcomes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductAndTankStockImportIntegrationTest extends ApiIntegrationTest {

    @Autowired private AncillaryProductRepository productRepository;
    @Autowired private AncillaryProductPriceRepository priceRepository;
    @Autowired private AncillaryStockLotRepository stockLotRepository;
    @Autowired private InventoryLotRepository inventoryLotRepository;

    @Test
    void productsArriveWithPriceHsnAndOpeningStockAsTheirFirstLot() throws Exception {
        PumpSetup setup = createPumpWithStock("0");

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/products", setup.pumpId()), setup.owner(), rows(
                product(2, "Engine Oil", "litre", "1", Map.of("brand", "Castrol", "selling_price", "420", "hsn_code", "27101980",
                        "gst_rate", "18", "opening_stock", "24", "opening_cost", "330.50", "low_stock_threshold", "5")),
                product(3, "Coolant", "LITRE", "0.5", Map.of())))
                .andExpect(status().isOk()));

        assertThat(report.get("createCount").asInt()).isEqualTo(2);
        AncillaryProduct oil = productNamed(setup, "Engine Oil");
        assertThat(oil.getCurrentStockUnits()).isEqualTo(24);
        assertThat(oil.getHsnCode()).isEqualTo("27101980");
        assertThat(oil.getLowStockThreshold()).isEqualTo(5);
        assertThat(priceRepository.findFirstByProductIdOrderByEffectiveFromDesc(oil.getId()).orElseThrow().getPricePerUnit())
                .isEqualByComparingTo("420");
        List<AncillaryStockLot> lots = stockLotRepository.findAllActiveLotsByProductIds(List.of(oil.getId()));
        assertThat(lots).singleElement().satisfies(lot -> {
            assertThat(lot.getRemainingQuantity()).isEqualTo(24);
            assertThat(lot.getCostPricePerUnit()).isEqualByComparingTo("330.50");
            assertThat(lot.getDeliveryDate()).isEqualTo(TestDates.today());
        });
        AncillaryProduct coolant = productNamed(setup, "Coolant");
        assertThat(coolant.getCurrentStockUnits()).isZero();
        assertThat(stockLotRepository.findAllActiveLotsByProductIds(List.of(coolant.getId()))).isEmpty();
    }

    @Test
    void openingTankStockBecomesTheTanksFirstLotAtItsCost() throws Exception {
        PumpSetup setup = createPumpWithStock("0");
        UndergroundTank diesel = addTank(setup, "Diesel Tank", FuelType.DIESEL, TankStatus.ACTIVE);

        JsonNode report = json(perform(post("/api/pumps/{pumpId}/imports/tank-stock", setup.pumpId()), setup.owner(), rows(
                tankRow(2, setup.tank().getTankIdentifier().toLowerCase(), "9000.5", "96.125"),
                tankRow(3, "Diesel Tank", "12000", "84.10")))
                .andExpect(status().isOk()));

        assertThat(report.get("createCount").asInt()).isEqualTo(2);
        UndergroundTank petrol = tankRepository.findById(setup.tank().getId()).orElseThrow();
        assertThat(petrol.getCurrentStock()).isEqualByComparingTo("9000.5");
        List<InventoryLot> lots = inventoryLotRepository.findAllByTankIdOrdered(petrol.getId());
        assertThat(lots).singleElement().satisfies(lot -> {
            assertThat(lot.getOriginalQuantity()).isEqualByComparingTo("9000.5");
            assertThat(lot.getCostPricePerUnit()).isEqualByComparingTo("96.125");
            assertThat(lot.getTankerDeliveryId()).isNull();
            assertThat(lot.getIsDipAdjustment()).isFalse();
            assertThat(lot.getDeliveryDate()).isEqualTo(TestDates.today().atStartOfDay(TestDates.BUSINESS_ZONE).toOffsetDateTime());
        });
        assertThat(tankRepository.findById(diesel.getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("12000");

        JsonNode second = json(perform(post("/api/pumps/{pumpId}/imports/tank-stock", setup.pumpId()), setup.owner(),
                rows(tankRow(2, setup.tank().getTankIdentifier(), "9000.5", "96.125"))).andExpect(status().isOk()));
        assertThat(outcomes(second)).containsExactly("SKIP");
        assertThat(tankRepository.findById(setup.tank().getId()).orElseThrow().getCurrentStock()).isEqualByComparingTo("9000.5");
    }

    private UndergroundTank addTank(PumpSetup setup, String identifier, FuelType fuelType, TankStatus status) {
        return tankRepository.save(UndergroundTank.builder()
                .pumpId(setup.pumpId()).tankIdentifier(identifier).fuelType(fuelType)
                .capacity(new BigDecimal("20000")).currentStock(BigDecimal.ZERO).dipTolerance(new BigDecimal("50"))
                .status(status).build());
    }

    private AncillaryProduct productNamed(PumpSetup setup, String name) {
        return productRepository.findByPumpId(setup.pumpId()).stream()
                .filter(product -> product.getName().equals(name)).findFirst().orElseThrow();
    }

    @SafeVarargs
    private static Map<String, Object> rows(Map<String, Object>... rows) {
        return Map.of("rows", List.of(rows));
    }

    private static Map<String, Object> product(int rowNumber, String name, String unit, String packageSize,
                                               Map<String, String> extra) {
        Map<String, Object> row = new HashMap<>();
        row.put("rowNumber", rowNumber);
        row.put("name", name);
        row.put("unit", unit);
        row.put("packageSize", packageSize);
        row.put("brand", extra.get("brand"));
        row.put("gstRate", extra.get("gst_rate"));
        row.put("hsnCode", extra.get("hsn_code"));
        row.put("sellingPrice", extra.get("selling_price"));
        row.put("lowStockThreshold", extra.get("low_stock_threshold"));
        row.put("openingStock", extra.get("opening_stock"));
        row.put("openingCost", extra.get("opening_cost"));
        return row;
    }

    private static Map<String, Object> tankRow(int rowNumber, String tank, String litres, String cost) {
        return Map.of("rowNumber", rowNumber, "tank", tank, "litres", litres, "costPerLitre", cost);
    }
}
