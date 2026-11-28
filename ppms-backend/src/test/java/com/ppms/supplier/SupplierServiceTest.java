package com.ppms.supplier;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;
import com.ppms.common.time.BusinessClock;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierServiceTest {

    private static final Long PUMP_ID = 2L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private final FuelSupplierRepository supplierRepository = mock(FuelSupplierRepository.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final BusinessClock businessClock = mock(BusinessClock.class);

    private final SupplierService service = new SupplierService(supplierRepository, pumpLocationRepository,
            mock(AuditService.class), businessClock);

    private final User owner = User.builder().id(1L).role(UserRole.OWNER).fullName("Owner").build();
    private final FuelSupplier iocl = supplier(11L, "IOCL");
    private final FuelSupplier bpcl = supplier(12L, "BPCL");

    @BeforeEach
    void setUp() {
        when(businessClock.today()).thenReturn(TODAY);
        when(supplierRepository.findByIdAndPumpId(11L, PUMP_ID)).thenReturn(Optional.of(iocl));
        when(supplierRepository.findByIdAndPumpId(12L, PUMP_ID)).thenReturn(Optional.of(bpcl));
        when(supplierRepository.save(any(FuelSupplier.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pumpLocationRepository.findByIdForUpdate(PUMP_ID)).thenReturn(Optional.of(new PumpLocation()));
    }

    @Test
    void deliveryTakesThePickedSupplierElseTheDefaultElseNone() {
        when(supplierRepository.findByPumpIdAndDefaultSupplierTrue(PUMP_ID)).thenReturn(Optional.empty());
        assertThat(service.supplierForDelivery(PUMP_ID, null)).isNull();

        when(supplierRepository.findByPumpIdAndDefaultSupplierTrue(PUMP_ID)).thenReturn(Optional.of(iocl));
        assertThat(service.supplierForDelivery(PUMP_ID, null)).isSameAs(iocl);
        assertThat(service.supplierForDelivery(PUMP_ID, 12L)).isSameAs(bpcl);
    }

    @Test
    void inactiveSupplierCannotTakeADeliveryOrBecomeTheDefault() {
        bpcl.setActive(false);

        assertThatThrownBy(() -> service.supplierForDelivery(PUMP_ID, 12L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_INACTIVE));
        assertThatThrownBy(() -> service.makeDefault(PUMP_ID, 12L, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_INACTIVE));
    }

    @Test
    void newDefaultSupplierReplacesTheOldOne() {
        iocl.setDefaultSupplier(true);
        when(supplierRepository.findByPumpIdAndDefaultSupplierTrue(PUMP_ID)).thenReturn(Optional.of(iocl));

        FuelSupplier saved = service.makeDefault(PUMP_ID, 12L, owner);

        assertThat(saved.isDefaultSupplier()).isTrue();
        assertThat(iocl.isDefaultSupplier()).isFalse();
        verify(supplierRepository).saveAndFlush(iocl);
    }

    @Test
    void openingBalanceNeedsADateThatIsNotInTheFuture() {
        UpsertSupplierRequest request = new UpsertSupplierRequest();
        request.setName("IOCL");
        request.setOpeningBalance(new BigDecimal("10000"));

        assertThatThrownBy(() -> service.updateSupplier(PUMP_ID, 11L, request, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_OPENING_BALANCE_DATE_REQUIRED));

        request.setOpeningBalanceDate(TODAY.plusDays(1));
        assertThatThrownBy(() -> service.updateSupplier(PUMP_ID, 11L, request, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ErrorCodes.SUPPLIER_OPENING_BALANCE_DATE_IN_FUTURE));

        request.setOpeningBalanceDate(TODAY.minusDays(90));
        FuelSupplier updated = service.updateSupplier(PUMP_ID, 11L, request, owner);
        assertThat(updated.getOpeningBalance()).isEqualByComparingTo("10000.00");
        assertThat(updated.getOpeningBalanceDate()).isEqualTo(TODAY.minusDays(90));
    }

    private static FuelSupplier supplier(Long id, String name) {
        return FuelSupplier.builder().id(id).pumpId(PUMP_ID).name(name).active(true).build();
    }
}
