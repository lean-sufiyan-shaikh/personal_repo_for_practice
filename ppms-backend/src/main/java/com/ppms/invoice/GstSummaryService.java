package com.ppms.invoice;

import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductQueryService;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillaryStockDelivery;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.invoice.GstSummaryResponse.PurchaseLine;
import com.ppms.invoice.GstSummaryResponse.TaxBucket;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.transaction.TransactionPaymentMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A month's GST: output tax on counter products (from invoices issued in the month and from counter sales paid on the
 * spot, which are sold within the pump's state, less GST credit notes issued in the month), fuel sales as non-GST supplies, and input tax from counter-product
 * purchase bills dated in the month.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GstSummaryService {

    static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final GstSummaryRepository summaryRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final AncillaryProductRepository productRepository;

    public GstSummaryResponse summaryOf(Long pumpId, String month) {
        YearMonth yearMonth = parseMonth(month);
        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found: " + pumpId));

        TaxBucket fromInvoices = invoicedTax(summaryRepository.sumInvoicedCounterProductTax(pumpId, from, to));
        List<AncillarySale> counterSales = summaryRepository.findPaidCounterSales(pumpId, from, to,
                TransactionPaymentMode.CREDIT);
        TaxBucket fromCounterSales = counterSales.stream().map(GstSummaryService::taxOf)
                .reduce(TaxBucket.NONE, TaxBucket::plus);
        int salesWithoutGst = (int) counterSales.stream().filter(sale -> sale.getGstRatePercent().signum() == 0).count();
        TaxBucket creditNotes = invoicedTax(summaryRepository.sumCreditNoteCounterProductTax(pumpId, from, to));
        TaxBucket outward = fromInvoices.plus(fromCounterSales).minus(creditNotes);

        List<AncillaryStockDelivery> purchases = summaryRepository.findPurchasesWithGst(pumpId, from, to);
        List<PurchaseLine> purchaseLines = purchaseLinesOf(purchases);
        TaxBucket input = purchaseLines.stream()
                .map(line -> new TaxBucket(line.taxableValue(), line.cgst(), line.sgst(), line.igst()))
                .reduce(TaxBucket.NONE, TaxBucket::plus);

        return new GstSummaryResponse(yearMonth.toString(), from, to, pump.getGstin(), outward, fromInvoices,
                fromCounterSales, creditNotes, money(summaryRepository.sumFuelSales(pumpId, from, to)), input,
                outward.minusTax(input), salesWithoutGst, purchaseLines);
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(ErrorCodes.GST_SUMMARY_MONTH_INVALID, Map.of("month", String.valueOf(month)),
                    "Month must be given as YYYY-MM, e.g. 2026-11.");
        }
    }

    private static TaxBucket invoicedTax(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return TaxBucket.NONE;
        }
        Object[] row = rows.get(0);
        return new TaxBucket(money((BigDecimal) row[0]), money((BigDecimal) row[1]), money((BigDecimal) row[2]),
                money((BigDecimal) row[3]));
    }

    /**
     * A sale that stored its GST is taxed on its pre-tax total; one rung up at a tax-inclusive price has the GST taken
     * out of the price paid at its rate, as an invoice line does. Counter sales are within the state, so CGST + SGST.
     */
    static TaxBucket taxOf(AncillarySale sale) {
        BigDecimal taxableValue;
        BigDecimal gst;
        if (sale.getGstAmount().signum() > 0) {
            taxableValue = money(sale.getTotalAmount());
            gst = money(sale.getGstAmount());
        } else {
            BigDecimal pricePaid = money(sale.getTotalWithGst());
            taxableValue = pricePaid.multiply(HUNDRED)
                    .divide(HUNDRED.add(sale.getGstRatePercent()), 2, RoundingMode.HALF_UP);
            gst = pricePaid.subtract(taxableValue);
        }
        GstTaxSplit split = GstTaxSplit.of(gst, true);
        return new TaxBucket(taxableValue, split.cgst(), split.sgst(), split.igst());
    }

    private List<PurchaseLine> purchaseLinesOf(List<AncillaryStockDelivery> purchases) {
        Map<Long, AncillaryProduct> productById = productRepository.findAllById(
                        purchases.stream().map(AncillaryStockDelivery::getProductId).distinct().toList()).stream()
                .collect(Collectors.toMap(AncillaryProduct::getId, Function.identity()));
        return purchases.stream()
                .map(delivery -> {
                    AncillaryProduct product = productById.get(delivery.getProductId());
                    BigDecimal tax = delivery.getCgstAmount().add(delivery.getSgstAmount()).add(delivery.getIgstAmount());
                    return new PurchaseLine(delivery.getId(), delivery.getGstInvoiceDate(), delivery.getInvoiceReference(),
                            delivery.getGstSupplierName(), delivery.getGstSupplierGstin(),
                            product != null ? AncillaryProductQueryService.buildDisplayName(product) : null,
                            delivery.getGstTaxableValue(), delivery.getGstRatePercent(), delivery.getCgstAmount(),
                            delivery.getSgstAmount(), delivery.getIgstAmount(), delivery.getGstTaxableValue().add(tax));
                })
                .toList();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(2, RoundingMode.HALF_UP);
    }
}
