package com.ppms.collection;

import com.ppms.pump.PumpLocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Builds the upi://pay link a UPI app opens with the payee and amount filled in. PPMS does not learn whether the
 * client paid; payments are still recorded and approved by hand.
 */
public final class UpiPaymentLink {

    private static final Pattern UPI_ID = Pattern.compile("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,63}$");
    private static final Pattern PAYEE_NAME = Pattern.compile("^[\\p{L}\\p{M}0-9 .&'-]{1,100}$");
    private static final int MAX_NOTE_LENGTH = 50;

    private UpiPaymentLink() {
    }

    public static boolean isValidUpiId(String upiId) {
        return upiId != null && UPI_ID.matcher(upiId).matches();
    }

    public static boolean isValidPayeeName(String payeeName) {
        return payeeName != null && !payeeName.isBlank() && PAYEE_NAME.matcher(payeeName).matches();
    }

    /** Null when the pump has no UPI details or nothing is owed. */
    public static String forPump(PumpLocation pump, BigDecimal amount, String note) {
        if (pump.getUpiId() == null || pump.getUpiPayeeName() == null) {
            return null;
        }
        return build(pump.getUpiId(), pump.getUpiPayeeName(), amount, note);
    }

    /** Null when nothing is owed. The UPI id is validated on save, so it goes in unescaped as UPI apps expect. */
    public static String build(String upiId, String payeeName, BigDecimal amount, String note) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        String shortNote = note.length() > MAX_NOTE_LENGTH ? note.substring(0, MAX_NOTE_LENGTH) : note;
        return "upi://pay?pa=" + upiId
                + "&pn=" + encode(payeeName)
                + "&am=" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + "&cu=INR"
                + "&tn=" + encode(shortNote);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
