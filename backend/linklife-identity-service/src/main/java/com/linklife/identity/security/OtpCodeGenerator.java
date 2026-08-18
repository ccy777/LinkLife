package com.linklife.identity.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Login OTP generator (Final-Audit-R1-D).
 *
 * <p>Uses JDK {@link SecureRandom} to produce a fixed-length 6-digit code in
 * the inclusive range 000000..999999. Leading zeros are allowed and preserved
 * (the code is formatted with %06d). Legacy non-secure random sources are
 * intentionally not used for verification codes.
 */
@Component
public class OtpCodeGenerator {

    private static final int CODE_BOUND = 1_000_000;
    private static final String SIX_DIGIT_FORMAT = "%06d";

    private final SecureRandom secureRandom;

    public OtpCodeGenerator() {
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generate a new 6-digit verification code (000000..999999).
     */
    public String generate() {
        return format(secureRandom.nextInt(CODE_BOUND));
    }

    /**
     * Format a value as a fixed 6-digit code; package-private for tests.
     */
    String format(int value) {
        return String.format(SIX_DIGIT_FORMAT, value);
    }
}
