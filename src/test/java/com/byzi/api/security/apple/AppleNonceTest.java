package com.byzi.api.security.apple;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class AppleNonceTest {

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void matchesWhenRawNonceHashesToTheClaim() throws Exception {
        String raw = "a3f1c0de-nonce-brut";
        assertThat(AppleNonce.matches(raw, sha256Hex(raw))).isTrue();
    }

    @Test
    void rejectsWhenRawNonceIsWrong() throws Exception {
        assertThat(AppleNonce.matches("mauvais", sha256Hex("le-bon"))).isFalse();
    }

    @Test
    void rejectsWhenEitherSideIsMissing() {
        assertThat(AppleNonce.matches(null, "abc")).isFalse();
        assertThat(AppleNonce.matches("", "abc")).isFalse();
        assertThat(AppleNonce.matches("raw", null)).isFalse();
        assertThat(AppleNonce.matches("raw", "")).isFalse();
    }
}
