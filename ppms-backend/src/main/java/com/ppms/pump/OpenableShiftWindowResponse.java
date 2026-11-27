package com.ppms.pump;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A shift window a shift can be opened for now. startsLater marks the next window within the early-start grace;
 * suggested marks the one the open-shift form preselects.
 */
public record OpenableShiftWindowResponse(Long definitionId, String name, LocalDate shiftDate, LocalTime startTime,
                                          LocalTime endTime, @JsonProperty("isNightShift") boolean isNightShift,
                                          boolean startsLater, boolean suggested) {

    static OpenableShiftWindowResponse of(CurrentShiftWindow window, boolean startsLater, boolean suggested) {
        PumpShiftDefinition definition = window.definition();
        return new OpenableShiftWindowResponse(definition.getId(), definition.getName(), window.shiftDate(),
                definition.getStartTime(), definition.exclusiveEndTime(), definition.isNightShift(), startsLater, suggested);
    }
}
