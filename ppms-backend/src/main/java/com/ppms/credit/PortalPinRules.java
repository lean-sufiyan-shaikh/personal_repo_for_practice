package com.ppms.credit;

import java.util.Set;

/** Which balance-page PINs are too easy to guess. New PINs must be six digits and not trivial. */
public final class PortalPinRules {

    static final int REQUIRED_LENGTH = 6;

    private static final Set<String> COMMON_PINS = Set.of(
            "123123", "121212", "112233", "123321", "111222", "159753", "147258", "696969", "101010",
            "007007", "520520", "131313", "232323", "000123", "100200", "202020", "786786", "999000");

    private PortalPinRules() {
    }

    /** One digit repeated, a run up or down, a short block repeated, or a common PIN. */
    public static boolean isTrivial(String pin) {
        return isOneDigitRepeated(pin) || isRun(pin, 1) || isRun(pin, -1) || isShortBlockRepeated(pin)
                || COMMON_PINS.contains(pin);
    }

    /** Shorter than six digits or trivial; such a PIN keeps working until the owner sets a new one. */
    public static boolean isWeak(String pin) {
        return pin.length() < REQUIRED_LENGTH || isTrivial(pin);
    }

    private static boolean isOneDigitRepeated(String pin) {
        return pin.chars().distinct().count() == 1;
    }

    private static boolean isRun(String pin, int step) {
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) - pin.charAt(i - 1) != step) {
                return false;
            }
        }
        return true;
    }

    private static boolean isShortBlockRepeated(String pin) {
        for (int block = 1; block <= pin.length() / 2; block++) {
            if (pin.length() % block == 0 && pin.substring(0, block).repeat(pin.length() / block).equals(pin)) {
                return true;
            }
        }
        return false;
    }
}
