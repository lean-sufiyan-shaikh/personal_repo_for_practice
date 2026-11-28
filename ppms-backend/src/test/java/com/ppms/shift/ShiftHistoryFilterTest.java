package com.ppms.shift;

import com.ppms.common.exception.BusinessException;
import com.ppms.fuel.FuelType;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class ShiftHistoryFilterTest {

    @Mock private ShiftRepository shiftRepository;
    @InjectMocks private ShiftService shiftService;

    @Test
    void fuelFilterCannotBeCombinedWithDiscrepanciesOnly() {
        ShiftHistoryFilter filter = new ShiftHistoryFilter(null, null, true, null, FuelType.PETROL);

        assertThatThrownBy(() -> shiftService.getShiftHistory(4L, filter, PageRequest.of(0, 10),
                User.builder().id(2L).role(UserRole.MANAGER).build()))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo("SHIFT_HISTORY_FILTER_CONFLICT"));
    }
}
