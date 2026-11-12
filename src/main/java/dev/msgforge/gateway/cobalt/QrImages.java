package dev.msgforge.gateway.cobalt;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;

/** QR do WhatsApp como PNG em data URL, o formato do campo "base64" da API. */
final class QrImages {

    private static final int SIZE = 400;
    private static final int MARGIN = 4;

    private QrImages() {}

    static String toDataUrl(String qr) {
        var png = new ByteArrayOutputStream();
        try {
            var matrix = new QRCodeWriter().encode(qr, BarcodeFormat.QR_CODE, SIZE, SIZE,
                    Map.of(EncodeHintType.MARGIN, MARGIN));
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (WriterException e) {
            throw new IllegalArgumentException("QR inválido", e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
