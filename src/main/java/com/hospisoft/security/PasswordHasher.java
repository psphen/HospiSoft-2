package com.hospisoft.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {

    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int ITERATIONS = 600_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Hashed(String saltBase64, String hashBase64) {
    }

    private PasswordHasher() {
    }

    public static Hashed hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt);
        return new Hashed(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(key));
    }

    public static boolean verify(char[] password, String saltBase64, String hashBase64) {
        if (password == null || saltBase64 == null || hashBase64 == null) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(saltBase64);
            expected = Base64.getDecoder().decode(hashBase64);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = derive(password, salt);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2 is not available in this JVM", ex);
        } finally {
            spec.clearPassword();
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: PasswordHasher <password> [<password> ...]");
            System.exit(1);
        }
        for (String password : args) {
            Hashed hashed = hash(password.toCharArray());
            System.out.println("password: " + password);
            System.out.println("salt:     " + hashed.saltBase64());
            System.out.println("hash:     " + hashed.hashBase64());
            System.out.println();
        }
    }
}
