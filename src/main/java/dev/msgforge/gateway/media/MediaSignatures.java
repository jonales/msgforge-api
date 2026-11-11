package dev.msgforge.gateway.media;

/**
 * Reconhece o formato pelos primeiros bytes. Serve para recusar cedo (400) um "vídeo" que não é
 * vídeo: com o ffmpeg instalado, o Cobalt chama o ffprobe para ler a duração, e com bytes
 * inválidos ele fica cerca de 10 s esperando.
 */
public final class MediaSignatures {

    private MediaSignatures() {}

    /** MP4, MOV e 3GP (caixa "ftyp" no byte 4) ou WebM/Matroska (cabeçalho EBML). */
    public static boolean isVideo(byte[] media) {
        if (media == null || media.length < 12) {
            return false;
        }
        var isoBmff = media[4] == 'f' && media[5] == 't' && media[6] == 'y' && media[7] == 'p';
        var ebml = (media[0] & 0xff) == 0x1A && (media[1] & 0xff) == 0x45
                && (media[2] & 0xff) == 0xDF && (media[3] & 0xff) == 0xA3;
        return isoBmff || ebml;
    }
}
