package com.ppms.pump;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.fuel.FuelType;
import com.ppms.shift.Shift;
import com.ppms.shift.ShiftRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DispensaryUnitServiceTest {

    private static final long PUMP_ID = 7L;
    private static final long DU_ID = 11L;
    private static final long NOZZLE_ID = 21L;

    @Mock private DispensaryUnitRepository duRepository;
    @Mock private NozzleRepository nozzleRepository;
    @Mock private PumpLocationRepository pumpLocationRepository;
    @Mock private UndergroundTankRepository tankRepository;
    @Mock private ShiftRepository shiftRepository;
    @Mock private AuditService auditService;

    @InjectMocks private DispensaryUnitService service;

    private final User owner = User.builder().id(3L).role(UserRole.OWNER).build();
    private final DispensaryUnit du = DispensaryUnit.builder().id(DU_ID).pumpId(PUMP_ID).duNumber(1).name("Machine 1").build();

    @Test
    void nozzleNumberAlreadyOnTheDispenserIsRefused() {
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        when(nozzleRepository.existsByDuIdAndNozzleNumber(DU_ID, 1)).thenReturn(true);

        assertAddRefused(nozzleInput(1, FuelType.PETROL), ErrorCodes.NOZZLE_NUMBER_ALREADY_ON_DU);
    }

    @Test
    void dispenserWithNineNozzlesTakesNoMore() {
        givenDispenserWith(9, false);

        assertAddRefused(nozzleInput(5, FuelType.PETROL), ErrorCodes.DU_NOZZLE_LIMIT_REACHED);
    }

    @Test
    void nozzleNumberOutsideOneToNineFailsValidation() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(nozzleInput(10, FuelType.PETROL))).hasSize(1);
        assertThat(validator.validate(nozzleInput(0, FuelType.PETROL))).hasSize(1);
        assertThat(validator.validate(nozzleInput(9, FuelType.PETROL))).isEmpty();
    }

    @Test
    void cngCannotJoinADispenserWithOtherFuels() {
        givenDispenserWith(1, false);

        assertAddRefused(nozzleInput(2, FuelType.CNG), ErrorCodes.DU_HAS_NON_CNG_NOZZLES);
    }

    @Test
    void otherFuelCannotJoinACngDispenser() {
        givenDispenserWith(1, true);

        assertAddRefused(nozzleInput(2, FuelType.PETROL), ErrorCodes.DU_IS_CNG_ONLY);
    }

    @Test
    void eighthNozzleOfTheSameKindIsAdded() {
        givenDispenserWith(7, false);

        service.addNozzle(PUMP_ID, DU_ID, nozzleInput(8, FuelType.DIESEL), owner);

        verify(nozzleRepository).save(any(Nozzle.class));
    }

    @Test
    void nozzleOfAnotherPumpsDispenserIsNotFound() {
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addNozzle(PUMP_ID, DU_ID, nozzleInput(2, FuelType.PETROL), owner))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void newDispenserMixingCngWithOtherFuelsIsRefused() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump(4)));

        assertCreateRefused(List.of(nozzleInput(1, FuelType.CNG), nozzleInput(2, FuelType.PETROL)),
                ErrorCodes.DU_CNG_MIXED_WITH_OTHER_FUELS);
    }

    @Test
    void newDispenserRepeatingANozzleNumberIsRefused() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump(4)));

        assertCreateRefused(List.of(nozzleInput(1, FuelType.PETROL), nozzleInput(1, FuelType.DIESEL)),
                ErrorCodes.DUPLICATE_NOZZLE_NUMBERS);
    }

    @Test
    void newDispenserBeyondThePumpsLimitIsRefused() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump(2)));
        when(duRepository.countByPumpId(PUMP_ID)).thenReturn(2L);

        assertCreateRefused(List.of(nozzleInput(1, FuelType.PETROL)), ErrorCodes.DU_LIMIT_REACHED);
    }

    @Test
    void nozzleOnAnOpenShiftCannotBeDisabled() {
        Nozzle nozzle = activeNozzle();
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        when(nozzleRepository.findByIdAndDuId(NOZZLE_ID, DU_ID)).thenReturn(Optional.of(nozzle));
        when(shiftRepository.findOpenShiftByNozzle(NOZZLE_ID)).thenReturn(Optional.of(Shift.builder().id(5L).build()));

        assertThatThrownBy(() -> service.updateNozzleStatus(PUMP_ID, DU_ID, NOZZLE_ID, NozzleStatus.INACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.NOZZLE_DISABLE_BLOCKED_BY_OPEN_SHIFT);
        assertThat(nozzle.getStatus()).isEqualTo(NozzleStatus.ACTIVE);
    }

    @Test
    void nozzleAlreadyInTheAskedStatusIsRefused() {
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        when(nozzleRepository.findByIdAndDuId(NOZZLE_ID, DU_ID)).thenReturn(Optional.of(activeNozzle()));

        assertThatThrownBy(() -> service.updateNozzleStatus(PUMP_ID, DU_ID, NOZZLE_ID, NozzleStatus.ACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.NOZZLE_ALREADY_IN_STATUS);
    }

    @Test
    void idleNozzleIsDisabledAndEnabledAgain() {
        Nozzle nozzle = activeNozzle();
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        when(nozzleRepository.findByIdAndDuId(NOZZLE_ID, DU_ID)).thenReturn(Optional.of(nozzle));

        service.updateNozzleStatus(PUMP_ID, DU_ID, NOZZLE_ID, NozzleStatus.INACTIVE, owner);
        assertThat(nozzle.getStatus()).isEqualTo(NozzleStatus.INACTIVE);
        service.updateNozzleStatus(PUMP_ID, DU_ID, NOZZLE_ID, NozzleStatus.ACTIVE, owner);
        assertThat(nozzle.getStatus()).isEqualTo(NozzleStatus.ACTIVE);
    }

    @Test
    void dispenserWithANozzleOnAnOpenShiftCannotBeDisabled() {
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        when(nozzleRepository.findByDuIdOrderByNozzleNumberAsc(DU_ID)).thenReturn(List.of(activeNozzle()));
        when(shiftRepository.findOpenShiftByNozzle(NOZZLE_ID)).thenReturn(Optional.of(Shift.builder().id(5L).build()));

        assertThatThrownBy(() -> service.updateDUStatus(PUMP_ID, DU_ID, NozzleStatus.INACTIVE, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCodes.DU_DISABLE_BLOCKED_BY_OPEN_SHIFT);
        assertThat(du.getStatus()).isEqualTo(NozzleStatus.ACTIVE);
    }

    private void givenDispenserWith(long nozzleCount, boolean cngOnly) {
        when(duRepository.findByIdAndPumpId(DU_ID, PUMP_ID)).thenReturn(Optional.of(du));
        lenient().when(nozzleRepository.existsByDuIdAndFuelType(DU_ID, FuelType.CNG)).thenReturn(cngOnly);
        lenient().when(nozzleRepository.countByDuId(DU_ID)).thenReturn(nozzleCount);
    }

    private void assertAddRefused(CreateDURequest.NozzleInput input, String code) {
        assertThatThrownBy(() -> service.addNozzle(PUMP_ID, DU_ID, input, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
        verify(nozzleRepository, never()).save(any());
    }

    private void assertCreateRefused(List<CreateDURequest.NozzleInput> nozzles, String code) {
        CreateDURequest request = new CreateDURequest();
        request.setName("Machine 2");
        request.setNozzles(nozzles);

        assertThatThrownBy(() -> service.createDU(PUMP_ID, request, owner))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
        verify(duRepository, never()).save(any());
    }

    private static PumpLocation pump(int maxDuCount) {
        return PumpLocation.builder().id(PUMP_ID).maxDuCount(maxDuCount).build();
    }

    private static Nozzle activeNozzle() {
        return Nozzle.builder().id(NOZZLE_ID).duId(DU_ID).nozzleNumber(1).fuelType(FuelType.PETROL).build();
    }

    private static CreateDURequest.NozzleInput nozzleInput(int number, FuelType fuelType) {
        CreateDURequest.NozzleInput input = new CreateDURequest.NozzleInput();
        input.setNozzleNumber(number);
        input.setFuelType(fuelType);
        input.setInitialReading(BigDecimal.ZERO);
        return input;
    }
}
