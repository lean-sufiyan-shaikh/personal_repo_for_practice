package com.ppms.onboarding;

import java.util.List;

/** The rows of a counter product import file, each with its optional opening stock and the cost it was bought at. */
public record ProductImportRequest(List<Row> rows) {

    public record Row(int rowNumber, String name, String unit, String packageSize, String brand, String variant,
                      String gstRate, String hsnCode, String sellingPrice, String lowStockThreshold,
                      String openingStock, String openingCost) {
    }
}
