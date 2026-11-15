package dev.msgforge.gateway.media;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/**
 * Foto de perfil e de grupo no formato que o recorte do WhatsApp Web gera: JPEG quadrado de
 * {@value #SIZE}x{@value #SIZE}, baseline, sem EXIF nem outros metadados (o canvas do navegador não
 * os escreve). Corta o centro, preenche a transparência de branco e reescreve a imagem; o servidor
 * respondia 406 (not-acceptable) a uma foto enviada como veio.
 */
public final class ProfilePictures {

    public static final int SIZE = 640;
    private static final float QUALITY = 0.9f;

    private ProfilePictures() {}

    /** @throws IllegalArgumentException se não for uma imagem que o Java lê (JPEG CMYK, por exemplo) */
    public static byte[] normalize(byte[] image) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(image));
        } catch (IOException e) {
            source = null;
        }
        if (source == null) {
            throw new IllegalArgumentException("unreadable image");
        }
        var side = Math.min(source.getWidth(), source.getHeight());
        var x = (source.getWidth() - side) / 2;
        var y = (source.getHeight() - side) / 2;
        var square = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        var g = square.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, SIZE, SIZE);
            g.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            g.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(square, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("could not encode the picture", e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
