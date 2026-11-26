package com.ppms.cash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SlipFileTypeTest {

    @Test
    void recognisesJpegPngAndPdfFromTheirFirstBytes() {
        assertThat(SlipFileType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00})).contains("image/jpeg");
        assertThat(SlipFileType.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0})).contains("image/png");
        assertThat(SlipFileType.detect("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).contains("application/pdf");
    }

    @Test
    void refusesAnythingElseWhateverItIsCalled() {
        assertThat(SlipFileType.detect("<svg onload=alert(1)>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(SlipFileType.detect(new byte[]{(byte) 0xFF})).isEmpty();
    }

    @Test
    void fileNameKeepsOnlySafeCharactersOrFallsBackToADefault() {
        assertThat(SlipFileType.safeFileName("slip 12\"; x.jpg", 5L, "image/jpeg")).isEqualTo("slip_12___x.jpg");
        assertThat(SlipFileType.safeFileName(null, 5L, "application/pdf")).isEqualTo("deposit-slip-5.pdf");
    }
}
