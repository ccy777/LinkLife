package com.linklife.identity.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手机号统一脱敏测试。
 */
class SensitiveDataMaskerTest {

    @Test
    void maskCommonPhoneKeepsLastFour() {
        assertThat(SensitiveDataMasker.maskPhone("13800138000")).isEqualTo("*******8000");
    }

    @Test
    void nullReturnsStablePlaceholder() {
        assertThat(SensitiveDataMasker.maskPhone(null)).isEqualTo("<null>");
    }

    @Test
    void emptyAndShortAreFullyMasked() {
        assertThat(SensitiveDataMasker.maskPhone("")).isEmpty();
        assertThat(SensitiveDataMasker.maskPhone("123")).isEqualTo("***");
        assertThat(SensitiveDataMasker.maskPhone("1234")).isEqualTo("****");
    }
}
