package dev.msgforge.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra segredos guardados no banco (hoje, a senha do proxy da instância), com AES-256-GCM (ADR 0014).
 *
 * <p>A chave vem de {@code SECRETS_ENCRYPTION_KEY} (32+ caracteres, ex.: {@code openssl rand -hex 32});
 * a chave AES é o SHA-256 dela. O id da instância entra como dado associado: o texto cifrado de uma
 * instância não abre em outra. Formato: {@code v1:} + base64(iv de 12 bytes || cifrado com a tag).
 * Sem a chave configurada, {@link #enabled()} é false e quem precisa cifrar recusa o pedido.
 */
@Component
public class SecretCipher {

    public static final int MIN_KEY_LENGTH = 32;
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${msgforge.secrets.encryption-key:}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            this.key = null;
            return;
        }
        if (encryptionKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("SECRETS_ENCRYPTION_KEY must have at least " + MIN_KEY_LENGTH
                    + " characters (ex.: openssl rand -hex 32)");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(UUID owner, String plain) {
        requireKey();
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            var sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            var out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o segredo", e);
        }
    }

    /** Lança {@link IllegalStateException} (sem o conteúdo) se a chave mudou ou o valor foi adulterado. */
    public String decrypt(UUID owner, String encrypted) {
        requireKey();
        if (encrypted == null || !encrypted.startsWith(PREFIX)) {
            throw new IllegalStateException("Segredo em formato desconhecido");
        }
        try {
            var in = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("O segredo não pôde ser decifrado (SECRETS_ENCRYPTION_KEY mudou?)");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("SECRETS_ENCRYPTION_KEY não configurada");
        }
    }
}
