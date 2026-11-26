package com.ppms.cash;

import java.util.Arrays;
import java.util.Optional;

/** Recognises the deposit slip formats that are accepted (JPEG, PNG, PDF) from the file's leading bytes. */
final class SlipFileType {

    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String PDF = "application/pdf";

    private static final byte[] JPEG_START = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_START = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_START = {'%', 'P', 'D', 'F', '-'};

    private SlipFileType() {
    }

    static Optional<String> detect(byte[] content) {
        if (startsWith(content, JPEG_START)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, PNG_START)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, PDF_START)) {
            return Optional.of(PDF);
        }
        return Optional.empty();
    }

    /** Letters, digits, dot, dash and underscore only, so the name is safe in a Content-Disposition header. */
    static String safeFileName(String originalName, Long depositId, String contentType) {
        String cleaned = originalName == null ? "" : originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isBlank() || cleaned.length() > 100) {
            return "deposit-slip-" + depositId + extensionOf(contentType);
        }
        return cleaned;
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case JPEG -> ".jpg";
            case PNG -> ".png";
            default -> ".pdf";
        };
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        return content.length >= prefix.length && Arrays.equals(content, 0, prefix.length, prefix, 0, prefix.length);
    }
}
