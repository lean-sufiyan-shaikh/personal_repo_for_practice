package com.ppms.shift;

import com.ppms.fuel.FuelType;
import com.ppms.pump.Nozzle;
import com.ppms.pump.PumpLocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShiftReadModelServiceTest {

    @Mock private ShiftReadingPriceSegmentRepository priceSegmentRepository;
    @Mock private ShiftInvestigationNoteRepository investigationNoteRepository;
    @Mock private UserRepository userRepository;
    @Mock private ShiftOperatorRepository shiftOperatorRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @InjectMocks private ShiftReadModelService service;

    @Test
    void listsNozzlesAndReadingsByNozzleNumberWhateverOrderTheyWereStoredIn() {
        Shift shift = Shift.builder().id(1L).pumpId(4L).duId(2L).operatorId(5L).status(ShiftStatus.OPEN).build();
        List<Nozzle> nozzles = List.of(nozzle(22L, 2, FuelType.DIESEL), nozzle(21L, 1, FuelType.PETROL));
        List<ShiftFuelReading> readings = List.of(reading(22L, FuelType.DIESEL), reading(21L, FuelType.PETROL));

        ShiftResponse response = service.toResponse(shift, null, nozzles, null, null, readings, List.of());

        assertThat(response.getNozzles()).extracting(ShiftResponse.NozzleSummary::nozzleNumber).containsExactly(1, 2);
        assertThat(response.getFuelReadings()).extracting(ShiftResponse.FuelReadingResponse::nozzleId).containsExactly(21L, 22L);
    }

    @Test
    void investigationHistoryListsEveryNoteInOrderWithItsWritersName() {
        Shift shift = Shift.builder().id(1L).pumpId(4L).duId(2L).operatorId(5L)
                .status(ShiftStatus.CLOSED_DISCREPANCY_PENDING).investigationNote("CCTV shows a drive-off").build();
        OffsetDateTime first = OffsetDateTime.of(2026, 11, 28, 10, 0, 0, 0, ZoneOffset.UTC);
        when(investigationNoteRepository.findByShiftIdInOrderByWrittenAtAscIdAsc(List.of(1L))).thenReturn(List.of(
                ShiftInvestigationNote.builder().shiftId(1L).note("Checking CCTV").writtenByUserId(2L).writtenAt(first).build(),
                ShiftInvestigationNote.builder().shiftId(1L).note("CCTV shows a drive-off").writtenByUserId(3L)
                        .writtenAt(first.plusHours(1)).build()));
        when(userRepository.findAllById(any())).thenReturn(List.of(
                User.builder().id(2L).fullName("Manager").build(), User.builder().id(3L).fullName("Owner").build()));

        ShiftResponse response = service.toResponse(shift, null, List.of(), null, null, List.of(), List.of());

        assertThat(response.getInvestigationNote()).isEqualTo("CCTV shows a drive-off");
        assertThat(response.getInvestigationHistory())
                .extracting(ShiftResponse.InvestigationNoteResponse::note, ShiftResponse.InvestigationNoteResponse::writtenByName)
                .containsExactly(tuple("Checking CCTV", "Manager"), tuple("CCTV shows a drive-off", "Owner"));
    }

    private static Nozzle nozzle(long id, int nozzleNumber, FuelType fuelType) {
        return Nozzle.builder().id(id).nozzleNumber(nozzleNumber).fuelType(fuelType)
                .maxMeterValue(new BigDecimal("99999999.999")).build();
    }

    private static ShiftFuelReading reading(long nozzleId, FuelType fuelType) {
        return ShiftFuelReading.builder().shiftId(1L).nozzleId(nozzleId).fuelType(fuelType)
                .startReading(BigDecimal.ZERO).priceSnapshot(new BigDecimal("100.00")).build();
    }
}
