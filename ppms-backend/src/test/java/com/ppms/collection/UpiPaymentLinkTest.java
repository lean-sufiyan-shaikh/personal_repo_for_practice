package com.ppms.collection;

import com.ppms.pump.PumpLocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UpiPaymentLinkTest {

    @Test
    void linkCarriesThePayeeTheExactAmountInRupeesAndANote() {
        String link = UpiPaymentLink.build("ramfuels@okaxis", "Ram Fuels", new BigDecimal("1234.5"), "Sharma Transport");

        assertThat(link).isEqualTo(
                "upi://pay?pa=ramfuels@okaxis&pn=Ram%20Fuels&am=1234.50&cu=INR&tn=Sharma%20Transport");
    }

    @Test
    void namesAndNotesAreEscapedSoTheyCannotAddParameters() {
        String link = UpiPaymentLink.build("ram.fuels-1@ybl", "Ram & Sons", new BigDecimal("10"), "A&B=1?x #2 देव");

        assertThat(link).contains("pn=Ram%20%26%20Sons")
                .contains("tn=A%26B%3D1%3Fx%20%232%20%E0%A4%A6%E0%A5%87%E0%A4%B5")
                .doesNotContain("+");
    }

    @Test
    void longNotesAreCutToFiftyCharacters() {
        String link = UpiPaymentLink.build("ram@okaxis", "Ram", BigDecimal.ONE, "x".repeat(80));

        assertThat(link).endsWith("tn=" + "x".repeat(50));
    }

    @Test
    void noLinkWhenNothingIsOwedOrThePumpHasNoUpiDetails() {
        assertThat(UpiPaymentLink.build("ram@okaxis", "Ram", BigDecimal.ZERO, "x")).isNull();
        assertThat(UpiPaymentLink.build("ram@okaxis", "Ram", new BigDecimal("-5"), "x")).isNull();
        assertThat(UpiPaymentLink.forPump(PumpLocation.builder().build(), BigDecimal.TEN, "x")).isNull();
        assertThat(UpiPaymentLink.forPump(PumpLocation.builder().upiId("ram@okaxis").upiPayeeName("Ram").build(),
                BigDecimal.TEN, "x")).startsWith("upi://pay?pa=ram@okaxis");
    }

    @Test
    void upiIdMustLookLikeNameAtBank() {
        assertThat(UpiPaymentLink.isValidUpiId("ramfuels@okaxis")).isTrue();
        assertThat(UpiPaymentLink.isValidUpiId("9876543210@ybl")).isTrue();
        assertThat(UpiPaymentLink.isValidUpiId("ram.fuels_1-a@oksbi")).isTrue();
        assertThat(UpiPaymentLink.isValidUpiId("ramfuels")).isFalse();
        assertThat(UpiPaymentLink.isValidUpiId("ram@")).isFalse();
        assertThat(UpiPaymentLink.isValidUpiId("ram fuels@okaxis")).isFalse();
        assertThat(UpiPaymentLink.isValidUpiId("ram@ok&am=1")).isFalse();
        assertThat(UpiPaymentLink.isValidUpiId(null)).isFalse();
    }

    @Test
    void payeeNameAllowsLettersOfAnyScriptButNoLinkCharacters() {
        assertThat(UpiPaymentLink.isValidPayeeName("Ram Fuels & Sons")).isTrue();
        assertThat(UpiPaymentLink.isValidPayeeName("राम फ्यूल्स")).isTrue();
        assertThat(UpiPaymentLink.isValidPayeeName("Ram?pa=x")).isFalse();
        assertThat(UpiPaymentLink.isValidPayeeName("  ")).isFalse();
        assertThat(UpiPaymentLink.isValidPayeeName("x".repeat(101))).isFalse();
    }

    @Test
    void qrCodeIsInlineSvg() {
        String svg = QrCodeSvg.render("upi://pay?pa=ram@okaxis&pn=Ram&am=1.00&cu=INR&tn=x", 120);

        assertThat(svg).startsWith("<svg").contains("width='120'").contains("<path").endsWith("</svg>");
    }
}
