package dev.vatn.plugins.terminalphone;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

class QrHelper {

    static String toAscii(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 40, 40);
            StringBuilder sb = new StringBuilder();
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    sb.append(matrix.get(x, y) ? "██" : "  ");
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (WriterException e) {
            return "QR unavailable: " + e.getMessage();
        }
    }
}
