package dev.msgforge.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ProfilePicturesTest {

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** Um JPEG com um bloco EXIF (APP1) logo depois do SOI, como os de câmera de celular. */
    private static byte[] withExif(byte[] jpeg) {
        var exif = "Exif\0\0fake-camera-data".getBytes(StandardCharsets.ISO_8859_1);
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.writeBytes(exif);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void pngWithTransparencyBecomesASquareJpegWithoutMetadata() throws Exception {
        var png = encode(new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB), "png");
        var result = ProfilePictures.normalize(png);
        assertThat(result[0]).isEqualTo((byte) 0xFF);
        assertThat(result[1]).isEqualTo((byte) 0xD8);
        var image = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(image.getWidth()).isEqualTo(640);
        assertThat(image.getHeight()).isEqualTo(640);
        assertThat(image.getRGB(320, 320) & 0xFFFFFF).isEqualTo(0xFFFFFF);   // transparência vira branco
    }

    @Test
    void exifIsDroppedAndSmallImagesAreScaledUp() throws Exception {
        var camera = withExif(encode(new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB), "jpeg"));
        assertThat(new String(camera, StandardCharsets.ISO_8859_1)).contains("Exif");
        var result = ProfilePictures.normalize(camera);
        assertThat(new String(result, StandardCharsets.ISO_8859_1)).doesNotContain("Exif").contains("JFIF");
        assertThat(ImageIO.read(new ByteArrayInputStream(result)).getWidth()).isEqualTo(640);
    }

    @Test
    void unreadableBytesAreRefused() {
        assertThatThrownBy(() -> ProfilePictures.normalize(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
