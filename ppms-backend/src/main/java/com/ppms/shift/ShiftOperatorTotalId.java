package com.ppms.shift;

import java.io.Serializable;
import java.util.Objects;

public class ShiftOperatorTotalId implements Serializable {

    private Long shiftId;
    private Long operatorId;

    public ShiftOperatorTotalId() {}

    public ShiftOperatorTotalId(Long shiftId, Long operatorId) {
        this.shiftId = shiftId;
        this.operatorId = operatorId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShiftOperatorTotalId that)) return false;
        return Objects.equals(shiftId, that.shiftId) && Objects.equals(operatorId, that.operatorId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shiftId, operatorId);
    }
}
