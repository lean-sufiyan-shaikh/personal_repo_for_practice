package com.ppms.invoice;

import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.AncillaryStockDelivery;
import com.ppms.transaction.TransactionPaymentMode;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The month's figures behind the GST summary, each read with one query. */
public interface GstSummaryRepository extends Repository<CreditInvoice, Long> {

    /** [taxable value, CGST, SGST, IGST] of counter-product lines on invoices issued in the period and not cancelled. */
    @Query("""
            SELECT COALESCE(SUM(l.taxableValue), 0), COALESCE(SUM(l.cgst), 0), COALESCE(SUM(l.sgst), 0),
                   COALESCE(SUM(l.igst), 0)
            FROM CreditInvoiceLine l, CreditInvoice i
            WHERE l.invoiceId = i.id AND i.pumpId = :pumpId AND i.status = com.ppms.invoice.CreditInvoiceStatus.ISSUED
              AND i.issuedOn BETWEEN :from AND :to AND l.lineType = com.ppms.invoice.InvoiceLineType.ANCILLARY
            """)
    List<Object[]> sumInvoicedCounterProductTax(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);

    /** Counter sales paid on the spot; credit sales are taxed through the invoice that bills them. */
    @Query("""
            SELECT s FROM AncillarySale s
            WHERE s.pumpId = :pumpId AND s.saleDate BETWEEN :from AND :to
              AND s.paymentMode <> :creditMode
            """)
    List<AncillarySale> findPaidCounterSales(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                             @Param("to") LocalDate to,
                                             @Param("creditMode") TransactionPaymentMode creditMode);

    /** What the fuel sold on closed shifts dated in the period is worth; fuel is outside GST. */
    @Query("""
            SELECT COALESCE(SUM(r.salesAmount), 0) FROM ShiftFuelReading r, Shift s
            WHERE r.shiftId = s.id AND s.pumpId = :pumpId AND s.shiftDate BETWEEN :from AND :to
              AND s.status NOT IN ('OPEN', 'OPEN_OVERDUE', 'AUTO_CLOSED_OVERDUE')
              AND r.salesAmount IS NOT NULL
            """)
    BigDecimal sumFuelSales(@Param("pumpId") Long pumpId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT d FROM AncillaryStockDelivery d
            WHERE d.pumpId = :pumpId AND d.gstInvoiceDate BETWEEN :from AND :to
            ORDER BY d.gstInvoiceDate, d.id
            """)
    List<AncillaryStockDelivery> findPurchasesWithGst(@Param("pumpId") Long pumpId, @Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);
}
