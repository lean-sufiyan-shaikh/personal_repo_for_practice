package com.ppms.onboarding;

import com.ppms.ancillary.AncillaryInventoryWorkflowService;
import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.CreateProductRequest;
import com.ppms.ancillary.RecordStockDeliveryRequest;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.invoice.GstFields;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Imports counter products through the same service as the Add Product form. Opening stock is recorded as a stock
 * delivery dated today at the given cost, so it becomes the product's first FIFO lot. A product with the same name,
 * brand, variant and pack size already at the pump is skipped, so uploading the same file again adds nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    static final String NAME = "name";
    static final String UNIT = "unit";
    static final String PACKAGE_SIZE = "package_size";
    static final String BRAND = "brand";
    static final String VARIANT = "variant";
    static final String GST_RATE = "gst_rate";
    static final String HSN_CODE = "hsn_code";
    static final String SELLING_PRICE = "selling_price";
    static final String LOW_STOCK_THRESHOLD = "low_stock_threshold";
    static final String OPENING_STOCK = "opening_stock";
    static final String OPENING_COST = "opening_cost";

    static final String OPENING_STOCK_REFERENCE = "OPENING-STOCK";
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    private final AncillaryInventoryWorkflowService workflowService;
    private final AncillaryProductRepository productRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    private record CheckedRow(RowCheck check, String name, UnitOfMeasure unit, BigDecimal packageSize, String brand,
                              String variant, BigDecimal gstRate, String hsnCode, BigDecimal sellingPrice,
                              Integer lowStockThreshold, Integer openingStock, BigDecimal openingCost) {

        String productKey() {
            return productKey(name, brand, variant, packageSize, unit);
        }

        static String productKey(String name, String brand, String variant, BigDecimal packageSize, UnitOfMeasure unit) {
            return CellReader.lowerCaseKey(name) + "|" + CellReader.lowerCaseKey(brand) + "|"
                    + CellReader.lowerCaseKey(variant) + "|" + packageSize.stripTrailingZeros().toPlainString() + "|" + unit;
        }
    }

    @Transactional(readOnly = true)
    public ImportReport preview(Long pumpId, ProductImportRequest request) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        return ImportReport.of(checkRows(pumpId, request.rows()).stream().map(CheckedRow::check).toList(), false, List.of());
    }

    @Transactional
    public ImportReport importRows(Long pumpId, ProductImportRequest request, User currentUser) {
        ImportLimits.requireRowCount(request.rows(), ImportLimits.MAX_ROWS);
        pumpLocationRepository.findByIdForUpdate(pumpId);
        List<CheckedRow> rows = checkRows(pumpId, request.rows());
        List<RowCheck> checks = rows.stream().map(CheckedRow::check).toList();
        ImportLimits.requireNoErrors(ImportReport.of(checks, false, List.of()));

        int stockedCount = 0;
        for (CheckedRow row : rows) {
            if (!row.check().isCreated()) {
                continue;
            }
            AncillaryProduct product = workflowService.createProduct(pumpId, createRequestOf(row), currentUser);
            if (row.openingStock() != null && row.openingStock() > 0) {
                workflowService.recordDelivery(pumpId, product.getId(), openingStockOf(row), currentUser);
                stockedCount++;
            }
        }

        ImportReport report = ImportReport.of(checks, true, List.of());
        auditService.log(pumpId, AuditAction.PRODUCTS_IMPORTED, "AncillaryProduct", null,
                "Product import: " + report.createCount() + " added (" + stockedCount + " with opening stock), "
                        + report.skipCount() + " skipped as already present",
                currentUser);
        log.info("Products imported: pump={}, created={}, withStock={}, skipped={}, by={}",
                pumpId, report.createCount(), stockedCount, report.skipCount(), currentUser.getId());
        return report;
    }

    private static CreateProductRequest createRequestOf(CheckedRow row) {
        CreateProductRequest request = new CreateProductRequest();
        request.setName(row.name());
        request.setBrand(row.brand());
        request.setVariant(row.variant());
        request.setPackageSize(row.packageSize());
        request.setUnitOfMeasure(row.unit());
        request.setLowStockThreshold(row.lowStockThreshold());
        request.setGstRatePercent(row.gstRate());
        request.setHsnCode(row.hsnCode());
        request.setSellingPricePerUnit(row.sellingPrice());
        return request;
    }

    private RecordStockDeliveryRequest openingStockOf(CheckedRow row) {
        RecordStockDeliveryRequest request = new RecordStockDeliveryRequest();
        request.setQuantityUnits(row.openingStock());
        request.setCostPricePerUnit(row.openingCost().setScale(2, RoundingMode.HALF_UP));
        request.setDeliveryDate(businessClock.today());
        request.setInvoiceReference(OPENING_STOCK_REFERENCE);
        request.setNotes("Opening stock");
        return request;
    }

    private List<CheckedRow> checkRows(Long pumpId, List<ProductImportRequest.Row> rows) {
        List<CheckedRow> checked = rows.stream().map(ProductImportService::checkCells).toList();
        Set<String> existingKeys = productRepository.findByPumpId(pumpId).stream()
                .map(product -> CheckedRow.productKey(product.getName(), product.getBrand(), product.getVariant(),
                        product.getPackageSize(), product.getUnitOfMeasure()))
                .collect(Collectors.toSet());
        Map<String, Integer> firstRowByKey = new HashMap<>();
        for (CheckedRow row : checked) {
            if (row.name() == null || row.unit() == null || row.packageSize() == null) {
                continue;
            }
            Integer firstRow = firstRowByKey.putIfAbsent(row.productKey(), row.check().rowNumber());
            if (firstRow != null) {
                row.check().error(NAME, ErrorCodes.IMPORT_DUPLICATE_IN_FILE, Map.of("firstRow", firstRow));
            } else if (existingKeys.contains(row.productKey())) {
                row.check().skip(NAME, ErrorCodes.IMPORT_ALREADY_EXISTS, Map.of());
            }
        }
        return checked;
    }

    private static CheckedRow checkCells(ProductImportRequest.Row row) {
        RowCheck check = new RowCheck(row.rowNumber());
        String name = CellReader.requiredText(check, NAME, row.name(), 100);
        UnitOfMeasure unit = CellReader.choice(check, UNIT, row.unit(), true, List.of(UnitOfMeasure.values()));
        BigDecimal packageSize = CellReader.decimal(check, PACKAGE_SIZE, row.packageSize(), true,
                new BigDecimal("0.001"), new BigDecimal("1000000"), 3);
        String brand = CellReader.optionalText(check, BRAND, row.brand(), 100);
        String variant = CellReader.optionalText(check, VARIANT, row.variant(), 100);
        BigDecimal gstRate = CellReader.decimal(check, GST_RATE, row.gstRate(), false, BigDecimal.ZERO, new BigDecimal("100"), 2);
        String hsnCode = readHsnCode(check, row.hsnCode());
        BigDecimal sellingPrice = CellReader.decimal(check, SELLING_PRICE, row.sellingPrice(), false,
                new BigDecimal("0.01"), MAX_PRICE, 2);
        Integer lowStockThreshold = CellReader.wholeNumber(check, LOW_STOCK_THRESHOLD, row.lowStockThreshold(), false, 0, 1_000_000);
        Integer openingStock = CellReader.wholeNumber(check, OPENING_STOCK, row.openingStock(), false, 0, 1_000_000);
        BigDecimal openingCost = CellReader.decimal(check, OPENING_COST, row.openingCost(), false,
                new BigDecimal("0.01"), MAX_PRICE, 2);
        if (openingStock != null && openingStock > 0 && openingCost == null && CellReader.text(row.openingCost()) == null) {
            check.error(OPENING_COST, ErrorCodes.IMPORT_COST_REQUIRED_FOR_STOCK);
        }
        return new CheckedRow(check, name, unit, packageSize, brand, variant, gstRate, hsnCode, sellingPrice,
                lowStockThreshold, openingStock, openingCost);
    }

    /** The product form's own HSN check; its refusal becomes a problem on this cell rather than failing the file. */
    private static String readHsnCode(RowCheck check, String raw) {
        try {
            return GstFields.normaliseHsnCode(raw);
        } catch (BusinessException e) {
            check.error(HSN_CODE, e.getCode());
            return null;
        }
    }
}
