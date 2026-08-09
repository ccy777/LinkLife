package com.linklife.identity.security;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-Audit-R1-D: SecureRandom OTP contract.
 */
class OtpCodeGeneratorTest {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    private final OtpCodeGenerator generator = new OtpCodeGenerator();

    @Test
    void formatPreservesLeadingZeros() {
        assertThat(generator.format(0)).isEqualTo("000000");
        assertThat(generator.format(5)).isEqualTo("000005");
        assertThat(generator.format(12345)).isEqualTo("012345");
        assertThat(generator.format(999999)).isEqualTo("999999");
    }

    @Test
    void generatedCodeIsAlwaysSixDigits() {
        for (int i = 0; i < 10_000; i++) {
            String code = generator.generate();
            assertThat(code).matches(SIX_DIGITS);
        }
    }

    @Test
    void generatedCodeStaysWithinRange() {
        for (int i = 0; i < 10_000; i++) {
            int value = Integer.parseInt(generator.generate());
            assertThat(value).isBetween(0, 999_999);
        }
    }
}
