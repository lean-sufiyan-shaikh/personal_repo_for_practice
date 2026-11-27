package com.ppms.dayclose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A summary section's colour with the reasons behind it, worst first. A section with nothing to flag carries one
 * GREEN reason saying so, so every flag can be read in words.
 */
public record SectionFlag(FlagColour colour, List<Reason> reasons) {

    /** @param code names the reason for translated screens, filled with params; text stays the English wording */
    public record Reason(FlagColour colour, String code, Map<String, Object> params, String text) {}

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private final List<Reason> reasons = new ArrayList<>();

        Builder red(String code, Map<String, Object> params, String text) {
            reasons.add(new Reason(FlagColour.RED, code, params, text));
            return this;
        }

        Builder amber(String code, Map<String, Object> params, String text) {
            reasons.add(new Reason(FlagColour.AMBER, code, params, text));
            return this;
        }

        SectionFlag orGreen(String code, Map<String, Object> params, String text) {
            if (reasons.isEmpty()) {
                return new SectionFlag(FlagColour.GREEN, List.of(new Reason(FlagColour.GREEN, code, params, text)));
            }
            List<Reason> worstFirst = reasons.stream()
                    .sorted(Comparator.comparing(Reason::colour).reversed())
                    .toList();
            return new SectionFlag(worstFirst.get(0).colour(), worstFirst);
        }
    }
}
