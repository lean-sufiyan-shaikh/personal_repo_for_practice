package com.ppms.shift;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** One operator's share of a closed shift; the collection figures are null when the close did not split them. */
@Entity
@Table(name = "shift_operator_totals")
@IdClass(ShiftOperatorTotalId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftOperatorTotal {

    @Id
    @Column(name = "shift_id", nullable = false)
    private Long shiftId;

    @Id
    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "litres_sold", nullable = false)
    private BigDecimal litresSold;

    @Column(name = "amount_due", nullable = false)
    private BigDecimal amountDue;

    @Column(name = "cash_collected")
    private BigDecimal cashCollected;

    @Column(name = "upi_collected")
    private BigDecimal upiCollected;

    @Column(name = "card_collected")
    private BigDecimal cardCollected;

    @Column(name = "fleet_card_collected")
    private BigDecimal fleetCardCollected;

    @Column(name = "credit_total")
    private BigDecimal creditTotal;

    /** Everything the operator handed in, or null when the close did not split the collections. */
    public BigDecimal collected() {
        if (cashCollected == null) {
            return null;
        }
        return cashCollected.add(upiCollected).add(cardCollected).add(fleetCardCollected).add(creditTotal);
    }
}
