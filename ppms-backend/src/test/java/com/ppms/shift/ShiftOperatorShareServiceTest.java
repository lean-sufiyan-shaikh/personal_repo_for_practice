package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftOperatorShareServiceTest {

    private static final long SHIFT_ID = 30L;
    private static final long OWNER = 7L;
    private static final long SECOND = 8L;
    private static final long FIRST_NOZZLE = 11L;
    private static final long SECOND_NOZZLE = 12L;
    private static final BigDecimal MAX_METER = new BigDecimal("99999999.999");
    private static final Map<Long, BigDecimal> MAX_METERS = Map.of(FIRST_NOZZLE, MAX_METER, SECOND_NOZZLE, MAX_METER);

    @Mock private ShiftOperatorRepository shiftOperatorRepository;
    @Mock private ShiftNozzleTurnRepository turnRepository;
    @Mock private ShiftOperatorTotalRepository totalRepository;
    @InjectMocks private ShiftOperatorShareService shareService;

    @Test
    void splitWhoseCashDoesNotAddUpToTheShiftsCashIsRefused() {
        withWorkers(SECOND);
        CloseShiftRequest request = closeRequest("3000.00", collection(OWNER, "1500.00"), collection(SECOND, "1400.00"));

        assertThatThrownBy(() -> shareService.requireCollectionsAddUp(shift("3000.00"), request, BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.getCode()).isEqualTo("OPERATOR_COLLECTIONS_TOTAL_MISMATCH");
                    assertThat(refused.getParams()).containsEntry("collectionCode", "CASH");
                });
    }

    @Test
    void splitThatAddsUpIsAccepted() {
        withWorkers(SECOND);
        CloseShiftRequest request = closeRequest("3000.00", collection(OWNER, "1450.00"), collection(SECOND, "1550.00"));

        assertThatCode(() -> shareService.requireCollectionsAddUp(shift("3000.00"), request, BigDecimal.ZERO))
                .doesNotThrowAnyException();
    }

    @Test
    void operatorWhoDidNotWorkTheShiftCannotBeInTheSplit() {
        withWorkers();
        CloseShiftRequest request = closeRequest("1000.00", collection(OWNER, "500.00"), collection(SECOND, "500.00"));

        assertThatThrownBy(() -> shareService.requireCollectionsAddUp(shift("1000.00"), request, BigDecimal.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("OPERATOR_COLLECTIONS_NOT_ON_SHIFT"));
    }

    @Test
    void secondOperatorGetsTheLitresAfterTheSwapAndEachKeepsTheirOwnCollections() {
        withWorkers(SECOND);
        List<ShiftFuelReading> readings = List.of(reading(FIRST_NOZZLE, "1000.000", "1010.000"),
                reading(SECOND_NOZZLE, "2000.000", "2020.000"));
        List<ShiftNozzleTurn> turns = List.of(turn(SECOND_NOZZLE, OWNER, "2000.000", "2005.000"),
                turn(SECOND_NOZZLE, SECOND, "2005.000", "2020.000"));

        shareService.recordAtClose(shift("3000.00"), readings, turns, MAX_METERS,
                List.of(collection(OWNER, "1450.00"), collection(SECOND, "1550.00")));

        Map<Long, ShiftOperatorTotal> shares = savedShares();
        assertThat(shares.get(OWNER).getLitresSold()).isEqualByComparingTo("15.000");
        assertThat(shares.get(OWNER).getAmountDue()).isEqualByComparingTo("1500.00");
        assertThat(shares.get(OWNER).getCashCollected()).isEqualByComparingTo("1450.00");
        assertThat(shares.get(SECOND).getLitresSold()).isEqualByComparingTo("15.000");
        assertThat(shares.get(SECOND).getAmountDue()).isEqualByComparingTo("1500.00");
        assertThat(shares.get(SECOND).getCashCollected()).isEqualByComparingTo("1550.00");
    }

    @Test
    void relieverGetsTheLitresSoldDuringTheBreakAndNoCollectionsWithoutASplit() {
        withWorkers(SECOND);
        List<ShiftFuelReading> readings = List.of(reading(FIRST_NOZZLE, "1000.000", "1010.000"),
                reading(SECOND_NOZZLE, "2000.000", "2002.000"));
        List<ShiftNozzleTurn> turns = List.of(
                turn(FIRST_NOZZLE, OWNER, "1000.000", "1003.000"),
                turn(FIRST_NOZZLE, SECOND, "1003.000", "1008.000"),
                turn(FIRST_NOZZLE, OWNER, "1008.000", "1010.000"),
                turn(SECOND_NOZZLE, OWNER, "2000.000", "2002.000"),
                turn(SECOND_NOZZLE, SECOND, "2002.000", "2002.000"),
                turn(SECOND_NOZZLE, OWNER, "2002.000", "2002.000"));

        shareService.recordAtClose(shift("1200.00"), readings, turns, MAX_METERS, null);

        Map<Long, ShiftOperatorTotal> shares = savedShares();
        assertThat(shares.get(OWNER).getLitresSold()).isEqualByComparingTo("7.000");
        assertThat(shares.get(SECOND).getLitresSold()).isEqualByComparingTo("5.000");
        assertThat(shares.get(SECOND).getAmountDue()).isEqualByComparingTo("500.00");
        assertThat(shares.get(SECOND).collected()).isNull();
    }

    @Test
    void relieverStillOnTheNozzlesAtCloseKeepsTheLitresToTheClosingReading() {
        withWorkers(SECOND);
        List<ShiftFuelReading> readings = List.of(reading(FIRST_NOZZLE, "1000.000", "1010.000"),
                reading(SECOND_NOZZLE, "2000.000", "2000.000"));
        List<ShiftNozzleTurn> turns = List.of(
                turn(FIRST_NOZZLE, OWNER, "1000.000", "1004.000"),
                turn(FIRST_NOZZLE, SECOND, "1004.000", "1010.000"),
                turn(SECOND_NOZZLE, OWNER, "2000.000", "2000.000"),
                turn(SECOND_NOZZLE, SECOND, "2000.000", "2000.000"));

        shareService.recordAtClose(shift("1000.00"), readings, turns, MAX_METERS, null);

        Map<Long, ShiftOperatorTotal> shares = savedShares();
        assertThat(shares.get(SECOND).getLitresSold()).isEqualByComparingTo("6.000");
        assertThat(shares.get(SECOND).getAmountDue()).isEqualByComparingTo("600.00");
        assertThat(shares.get(OWNER).getAmountDue()).isEqualByComparingTo("400.00");
    }

    @Test
    void singleOperatorGetsTheWholeShiftAndItsCollections() {
        withWorkers();
        Shift shift = shift("1000.00");
        shift.setCashCollected(new BigDecimal("1000.00"));

        shareService.recordAtClose(shift, List.of(reading(FIRST_NOZZLE, "1000.000", "1010.000")), List.of(), MAX_METERS, null);

        Map<Long, ShiftOperatorTotal> shares = savedShares();
        assertThat(shares).containsOnlyKeys(OWNER);
        assertThat(shares.get(OWNER).getAmountDue()).isEqualByComparingTo("1000.00");
        assertThat(shares.get(OWNER).getCashCollected()).isEqualByComparingTo("1000.00");
        assertThat(shares.get(OWNER).collected().subtract(shares.get(OWNER).getAmountDue())).isEqualByComparingTo("0.00");
    }

    private void withWorkers(Long... others) {
        List<ShiftOperator> members = Arrays.stream(others)
                .map(id -> ShiftOperator.builder().shiftId(SHIFT_ID).operatorId(id).role(ShiftOperatorRole.ADDITIONAL).build())
                .toList();
        when(shiftOperatorRepository.findByShiftIdOrderByJoinedAtAscIdAsc(SHIFT_ID)).thenReturn(members);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ShiftOperatorTotal> savedShares() {
        ArgumentCaptor<Collection<ShiftOperatorTotal>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(totalRepository).saveAll(saved.capture());
        return saved.getValue().stream().collect(Collectors.toMap(ShiftOperatorTotal::getOperatorId,
                total -> total));
    }

    private static Shift shift(String totalDue) {
        return Shift.builder().id(SHIFT_ID).pumpId(4L).operatorId(OWNER).status(ShiftStatus.OPEN)
                .totalAmountDue(new BigDecimal(totalDue)).build();
    }

    private static ShiftFuelReading reading(long nozzleId, String start, String end) {
        BigDecimal startReading = new BigDecimal(start);
        BigDecimal endReading = new BigDecimal(end);
        return ShiftFuelReading.builder().shiftId(SHIFT_ID).nozzleId(nozzleId).fuelType(FuelType.PETROL)
                .startReading(startReading).endReading(endReading).unitsSold(endReading.subtract(startReading))
                .priceSnapshot(new BigDecimal("100.00")).build();
    }

    private static ShiftNozzleTurn turn(long nozzleId, long operatorId, String start, String end) {
        return ShiftNozzleTurn.builder().shiftId(SHIFT_ID).nozzleId(nozzleId).operatorId(operatorId)
                .startReading(new BigDecimal(start)).endReading(new BigDecimal(end)).build();
    }

    private static CloseShiftRequest closeRequest(String cash, CloseShiftRequest.OperatorCollectionRequest... split) {
        CloseShiftRequest request = new CloseShiftRequest();
        request.setCashCollected(new BigDecimal(cash));
        request.setUpiCollected(BigDecimal.ZERO);
        request.setCardCollected(BigDecimal.ZERO);
        request.setFleetCardCollected(BigDecimal.ZERO);
        request.setCreditTotal(BigDecimal.ZERO);
        request.setOperatorCollections(List.of(split));
        return request;
    }

    private static CloseShiftRequest.OperatorCollectionRequest collection(long operatorId, String cash) {
        return new CloseShiftRequest.OperatorCollectionRequest(operatorId, new BigDecimal(cash), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
