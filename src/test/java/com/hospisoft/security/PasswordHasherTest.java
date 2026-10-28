package com.hospisoft.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    @Test
    void hashThenVerifySucceeds() {
        PasswordHasher.Hashed hashed = PasswordHasher.hash("Admin123*".toCharArray());
        assertTrue(PasswordHasher.verify("Admin123*".toCharArray(), hashed.saltBase64(), hashed.hashBase64()));
    }

    @Test
    void wrongPasswordFails() {
        PasswordHasher.Hashed hashed = PasswordHasher.hash("Admin123*".toCharArray());
        assertFalse(PasswordHasher.verify("admin123*".toCharArray(), hashed.saltBase64(), hashed.hashBase64()));
    }

    @Test
    void samePasswordProducesDifferentSaltsAndHashes() {
        PasswordHasher.Hashed first = PasswordHasher.hash("secret".toCharArray());
        PasswordHasher.Hashed second = PasswordHasher.hash("secret".toCharArray());
        assertNotEquals(first.saltBase64(), second.saltBase64());
        assertNotEquals(first.hashBase64(), second.hashBase64());
    }

    @Test
    void saltIs16BytesAndKeyIs256Bits() {
        PasswordHasher.Hashed hashed = PasswordHasher.hash("x".toCharArray());
        assertEquals(16, Base64.getDecoder().decode(hashed.saltBase64()).length);
        assertEquals(32, Base64.getDecoder().decode(hashed.hashBase64()).length);
    }

    @Test
    void tamperedHashFails() {
        PasswordHasher.Hashed hashed = PasswordHasher.hash("secret".toCharArray());
        byte[] raw = Base64.getDecoder().decode(hashed.hashBase64());
        raw[0] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertFalse(PasswordHasher.verify("secret".toCharArray(), hashed.saltBase64(), tampered));
    }

    @Test
    void malformedStoredValuesFailInsteadOfThrowing() {
        assertFalse(PasswordHasher.verify("secret".toCharArray(), "not base64!", "zzz"));
        assertFalse(PasswordHasher.verify("secret".toCharArray(), null, null));
    }
}
