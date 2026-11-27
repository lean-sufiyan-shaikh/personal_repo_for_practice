package com.ppms.dayclose;

import com.ppms.attention.AttentionSeverity;
import com.ppms.attention.AttentionType;
import com.ppms.inventory.DeliveryCheckStatus;
import com.ppms.shift.DiscrepancyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The owner's one-page summary of a business day: sales and margin, cash, stock and credit, each with a flag. Built
 * live for the close preview and stored unchanged with every close. Figures come from the day's closed shifts only;
 * shifts still open are counted in openShiftCount and left out.
 */
public record DaySummary(LocalDate businessDate, OffsetDateTime builtAt, int closedShiftCount, int openShiftCount,
                         FlagColour overallFlag, List<ChecklistItem> warnings,
                         Sales sales, Cash cash, Stock stock, Credit credit) {

    public record Sales(SectionFlag flag, List<FuelSales> fuels,
                        BigDecimal fuelLitres, BigDecimal fuelRevenue, BigDecimal fuelCost, BigDecimal fuelMargin,
                        BigDecimal productRevenue, BigDecimal productCost, BigDecimal productMargin,
                        BigDecimal grossMargin) {}

    public record FuelSales(String fuelType, BigDecimal litres, BigDecimal revenue, BigDecimal cost, BigDecimal margin) {}

    /** shortageAmount and overAmount are positive totals of the shifts that were short or over. */
    public record Cash(SectionFlag flag, BigDecimal cash, BigDecimal upi, BigDecimal card, BigDecimal fleetCard,
                       BigDecimal credit, BigDecimal shortageAmount, int shortShiftCount, BigDecimal overAmount,
                       int overShiftCount, long confirmedDropCount, BigDecimal confirmedDropAmount,
                       long pendingDropCount, SafeCountResult safeCount, long depositCount, BigDecimal depositAmount) {}

    /** The day's last safe count. */
    public record SafeCountResult(BigDecimal countedAmount, BigDecimal expectedAmount, BigDecimal varianceAmount,
                                  DiscrepancyType varianceType, BigDecimal toleranceAmount, boolean beyondTolerance,
                                  OffsetDateTime countedAt) {}

    public record Stock(SectionFlag flag, List<TankVariance> tanks, List<DeliveryCheck> deliveries,
                        long openClaimCount, BigDecimal openClaimAmount) {}

    /** Figures of the reconciled period closed by the day's last dip; all null when the tank was not dipped that day. */
    public record TankVariance(Long tankId, String tankIdentifier, String fuelType, boolean dipped,
                               BigDecimal varianceLitres, BigDecimal variancePercent, Boolean withinTolerance) {}

    public record DeliveryCheck(Long deliveryId, String invoiceReference, String fuelType, BigDecimal invoicedLitres,
                                BigDecimal receivedLitres, BigDecimal varianceLitres, DeliveryCheckStatus checkStatus) {}

    public record Credit(SectionFlag flag, BigDecimal creditGiven, BigDecimal collections, int overdueClientCount,
                         BigDecimal overdueAmount, int overLimitClientCount, int openAttentionCount,
                         List<AttentionLine> attentionItems) {}

    public record AttentionLine(AttentionType type, AttentionSeverity severity, String reason, String reasonCode,
                                Map<String, Object> reasonParams) {}

    static FlagColour worstOf(SectionFlag... flags) {
        return Stream.of(flags).map(SectionFlag::colour).max(Enum::compareTo).orElse(FlagColour.GREEN);
    }
}
