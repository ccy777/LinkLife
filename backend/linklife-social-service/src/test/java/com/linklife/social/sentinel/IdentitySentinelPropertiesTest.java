package com.linklife.social.sentinel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Identity 熔断参数合法性：非法值启用时 fail-fast，disabled 时不校验，合法值通过。
 */
class IdentitySentinelPropertiesTest {

    @Test
    void validDefaultsPassValidation() {
        assertThatCode(new IdentitySentinelProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void disabledSkipsValidation() {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setEnabled(false);
        props.setExceptionRatio(0);
        props.setMinimumRequestAmount(0);
        props.setStatIntervalMs(-1);
        props.setTimeWindowSeconds(0);
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void exceptionRatioMustBeWithinExclusiveZeroToOne() {
        IdentitySentinelProperties zero = new IdentitySentinelProperties();
        zero.setExceptionRatio(0);
        assertThatThrownBy(zero::validate).isInstanceOf(IllegalStateException.class);

        IdentitySentinelProperties overOne = new IdentitySentinelProperties();
        overOne.setExceptionRatio(1.01);
        assertThatThrownBy(overOne::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void minimumRequestAmountMustBePositive() {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setMinimumRequestAmount(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void statIntervalMustBePositive() {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setStatIntervalMs(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeWindowSecondsMustBeAtLeastOne() {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setTimeWindowSeconds(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
