package com.ppms.onboarding;

import java.util.List;

/** The rows of an opening tank stock file: which tank, how many litres it holds now and what they cost per litre. */
public record TankStockImportRequest(List<Row> rows) {

    public record Row(int rowNumber, String tank, String litres, String costPerLitre) {
    }
}
