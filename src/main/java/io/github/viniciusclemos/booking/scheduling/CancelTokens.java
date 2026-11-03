package io.github.viniciusclemos.booking.scheduling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** The secret in a booking's cancel link, and the hash that is kept in its place. */
final class CancelTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CancelTokens() {
    }

    /** 144 random bits, written in 24 characters that are safe in a URL. */
    static String newToken() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // a plain SHA-256 is enough here: the token is long and random, there's nothing to guess
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
