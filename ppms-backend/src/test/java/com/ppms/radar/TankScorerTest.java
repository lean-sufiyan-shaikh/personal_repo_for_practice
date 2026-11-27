package com.ppms.radar;

import com.ppms.inventory.WetStockReconciliationResponse.Period;
import com.ppms.inventory.WetStockReconciliationResponse.PeriodStatus;
import com.ppms.inventory.WetStockReconciliationResponse.Summary;
import com.ppms.inventory.WetStockReconciliationResponse.SummaryStatus;
import com.ppms.inventory.WetStockReconciliationResponse.TankReconciliation;
import com.ppms.radar.LeakageRadarResponse.TankScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TankScorerTest {

    private static final LocalDate LAST_DAY = LocalDate.of(2026, 11, 27);

    @Test
    void fourWeeksInARowLosingAboveTheLimitScoresHigh() {
        TankScore score = TankScorer.score(tank(weeklyVariances("-20", "-20", "-20", "-20"), "-2.00"), LAST_DAY);

        // 4 of 4 loss weeks -> 40; 4 in a row -> 30; period loss 2% above the 0.5% limit -> 30
        assertThat(score.score()).isEqualTo(100);
        assertThat(score.band()).isEqualTo(RadarBand.HIGH);
        assertThat(score.weeks()).hasSize(4).allMatch(LeakageRadarResponse.WeekFigure::lossAboveLimit);
        assertThat(score.weeks().get(0).weekEnd()).isEqualTo(LAST_DAY.minusDays(21));
        assertThat(score.evidence()).hasSize(4).allMatch(evidence -> evidence.kind() == EvidenceKind.LOSS_WEEK);
    }

    @Test
    void lossesWithinTheLimitScoreNothing() {
        TankScore score = TankScorer.score(tank(weeklyVariances("-2", "1", "-3", "0"), "-0.10"), LAST_DAY);

        assertThat(score.score()).isZero();
        assertThat(score.band()).isEqualTo(RadarBand.LOW);
        assertThat(score.evidence()).isEmpty();
    }

    @Test
    void scatteredLossWeeksScoreTheirShareWithoutAStreak() {
        TankScore score = TankScorer.score(tank(weeklyVariances("-20", "0", "-20", "0"), "-0.30"), LAST_DAY);

        // 2 of 4 -> 20; no two in a row -> 0; period loss 0.3% is above half the 0.5% limit -> 15
        assertThat(score.score()).isEqualTo(35);
        assertThat(score.band()).isEqualTo(RadarBand.WATCH);
    }

    @Test
    void fewerThanThreeWeeksWithReconciledDipsIsNotEnoughData() {
        TankScore score = TankScorer.score(tank(weeklyVariances("-50", "-50"), "-5.00"), LAST_DAY);

        assertThat(score.band()).isEqualTo(RadarBand.NOT_ENOUGH_DATA);
        assertThat(score.score()).isZero();
        assertThat(score.evidence()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"0,LOW", "29,LOW", "30,WATCH", "59,WATCH", "60,HIGH", "100,HIGH"})
    void scoresFallIntoLowWatchAndHighBands(int score, RadarBand band) {
        assertThat(RadarBand.of(score)).isEqualTo(band);
    }

    /** One reconciled day per week, the oldest first, each selling 1,000 L with the given variance. */
    private static List<Period> weeklyVariances(String... variances) {
        List<Period> periods = new ArrayList<>();
        for (int i = 0; i < variances.length; i++) {
            LocalDate day = LAST_DAY.minusDays(7L * (variances.length - 1 - i));
            periods.add(new Period(day, day, 1, PeriodStatus.RECONCILED, null, null, new BigDecimal("1000"), null, null,
                    null, new BigDecimal(variances[i]), null, true, null, null, null));
        }
        return periods;
    }

    private static TankReconciliation tank(List<Period> periods, String periodPercent) {
        return new TankReconciliation(3L, "T1", "PETROL", new BigDecimal("50"), new BigDecimal("0.5"),
                new Summary(new BigDecimal("4000"), null, new BigDecimal(periodPercent), periods.size(), 0,
                        SummaryStatus.WITHIN_LIMITS), periods);
    }
}
