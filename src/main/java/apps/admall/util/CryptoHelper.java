package apps.admall.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utility class to encrypt and decrypt strings using AES/CBC/PKCS5Padding.
 * Provides URL-safe Base64 tokens suitable for query parameters.
 */
public class CryptoHelper {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    // Keys and Initialization Vectors must be exactly 16 bytes for AES-128
    private static final String SECRET_KEY = "AppAdmallSec#"; 
    private static final String INIT_VECTOR = "RandomVector1234";

    /**
     * Encrypts a plain text string.
     * @param value The plain text string to encrypt.
     * @return The URL-safe Base64 encoded encrypted token.
     */
     public static String encrypt(String value) {
        try {
            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv);

            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new RuntimeException("Encryption error: " + ex.getMessage(), ex);
        }
    }

    /**
     * Decrypts an encrypted token.
     * @param encrypted The URL-safe Base64 encoded encrypted token.
     * @return The decrypted plain text string.
     */
    public static String decrypt(String encrypted) {
        try {
            IvParameterSpec iv = new IvParameterSpec(INIT_VECTOR.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec skeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "AES");

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, skeySpec, iv);

            byte[] original = cipher.doFinal(Base64.getUrlDecoder().decode(encrypted));
            return new String(original, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException("Decryption error: " + ex.getMessage(), ex);
        }
    }
}
