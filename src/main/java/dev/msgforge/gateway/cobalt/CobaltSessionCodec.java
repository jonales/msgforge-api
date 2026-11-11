package dev.msgforge.gateway.cobalt;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Empacota as duas partes da sessão do Cobalt ({@code Keys} e {@code Store}, já serializadas em
 * protobuf) num blob só, para caber no {@code session_store.payload} sem mudar o schema, que
 * continua genérico, sem saber do motor.
 *
 * <p>Formato: {@code "MFC1"} + int (tamanho de keys, -1 = ausente) + keys + int (tamanho de
 * store, -1 = ausente) + store.
 */
final class CobaltSessionCodec {

    private static final int MAGIC = 0x4D464331; // "MFC1"

    record Parts(byte[] keys, byte[] store) {

        static final Parts EMPTY = new Parts(null, null);

        Parts withKeys(byte[] keys) {
            return new Parts(keys, store);
        }

        Parts withStore(byte[] store) {
            return new Parts(keys, store);
        }

        Optional<byte[]> keysOptional() {
            return Optional.ofNullable(keys);
        }

        Optional<byte[]> storeOptional() {
            return Optional.ofNullable(store);
        }
    }

    private CobaltSessionCodec() {}

    static byte[] encode(Parts parts) {
        int size = 4 + 4 + length(parts.keys()) + 4 + length(parts.store());
        var buffer = ByteBuffer.allocate(size).putInt(MAGIC);
        put(buffer, parts.keys());
        put(buffer, parts.store());
        return buffer.array();
    }

    /** @throws IllegalArgumentException se o blob não for deste formato (sessão corrompida ou de outro motor) */
    static Parts decode(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return Parts.EMPTY;
        }
        var buffer = ByteBuffer.wrap(blob);
        if (blob.length < 12 || buffer.getInt() != MAGIC) {
            throw new IllegalArgumentException("Sessão em formato desconhecido (esperado MFC1)");
        }
        var keys = get(buffer);
        var store = get(buffer);
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("Sessão com bytes sobrando");
        }
        return new Parts(keys, store);
    }

    private static int length(byte[] part) {
        return part == null ? 0 : part.length;
    }

    private static void put(ByteBuffer buffer, byte[] part) {
        if (part == null) {
            buffer.putInt(-1);
        } else {
            buffer.putInt(part.length).put(part);
        }
    }

    private static byte[] get(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new IllegalArgumentException("Sessão truncada");
        }
        int length = buffer.getInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > buffer.remaining()) {
            throw new IllegalArgumentException("Sessão truncada");
        }
        var part = new byte[length];
        buffer.get(part);
        return part;
    }
}
