package com.ppms.fleet;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one spelling of a vehicle registration used to compare and store it: upper case, without spaces, dashes or
 * dots ("mh-12 ab 1234" is "MH12AB1234"). Validation is lenient so state, BH-series and older formats all pass:
 * 4 to 12 letters and digits with at least one of each.
 */
public final class VehicleRegistrationNumber {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-/]+");
    private static final Pattern VALID = Pattern.compile("^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{4,12}$");

    private VehicleRegistrationNumber() {
    }

    /** Null for a blank registration. */
    public static String normalise(String registration) {
        if (registration == null) {
            return null;
        }
        String normalised = SEPARATORS.matcher(registration.trim()).replaceAll("").toUpperCase(Locale.ROOT);
        return normalised.isEmpty() ? null : normalised;
    }

    public static boolean isValid(String normalisedRegistration) {
        return normalisedRegistration != null && VALID.matcher(normalisedRegistration).matches();
    }
}
