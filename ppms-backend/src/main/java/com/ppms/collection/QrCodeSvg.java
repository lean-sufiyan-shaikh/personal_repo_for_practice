package com.ppms.collection;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/** Draws a QR code as inline SVG, so a printable page can carry it without loading an image. */
public final class QrCodeSvg {

    private static final int QUIET_ZONE_MODULES = 2;

    private QrCodeSvg() {
    }

    public static String render(String text, int sizePixels) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.MARGIN, QUIET_ZONE_MODULES,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Could not encode a QR code of " + text.length() + " characters", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns='http://www.w3.org/2000/svg' role='img' aria-label='UPI QR code' width='" + sizePixels
                + "' height='" + sizePixels + "' viewBox='0 0 " + matrix.getWidth() + " " + matrix.getHeight()
                + "' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/>"
                + "<path fill='#000' d='" + path + "'/></svg>";
    }
}
