package dev.msgforge.gateway.cobalt;

import com.google.zxing.client.j2se.MatrixToImageWriter;
import it.auties.whatsapp.api.QrHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/** QR do WhatsApp como PNG em data URL, o formato do campo "base64" da Evolution. */
final class QrImages {

    private static final int SIZE = 400;
    private static final int MARGIN = 4;

    private QrImages() {}

    static String toDataUrl(String qr) {
        var matrix = QrHandler.createMatrix(qr, SIZE, MARGIN);
        var png = new ByteArrayOutputStream();
        try {
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }
}
