package com.linklife.merchant.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MerchantLocalCacheProperties：合法默认值、disabled 可 bypass、非法参数 fail-fast。
 */
class MerchantLocalCachePropertiesTest {

    @Test
    void validDefaultsPassValidation() {
        assertThatCode(new MerchantLocalCacheProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void disabledSkipsValidation() {
        MerchantLocalCacheProperties props = new MerchantLocalCacheProperties();
        props.setEnabled(false);
        props.setShopMaximumSize(0);
        props.setShopTtlSeconds(0);
        props.setShopTypeMaximumSize(-1);
        props.setShopTypeTtlSeconds(-5);
        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void shopMaximumSizeMustBePositiveWhenEnabled() {
        MerchantLocalCacheProperties props = new MerchantLocalCacheProperties();
        props.setShopMaximumSize(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shopTtlMustBePositiveWhenEnabled() {
        MerchantLocalCacheProperties props = new MerchantLocalCacheProperties();
        props.setShopTtlSeconds(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shopTypeMaximumSizeMustBePositiveWhenEnabled() {
        MerchantLocalCacheProperties props = new MerchantLocalCacheProperties();
        props.setShopTypeMaximumSize(-1);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shopTypeTtlMustBePositiveWhenEnabled() {
        MerchantLocalCacheProperties props = new MerchantLocalCacheProperties();
        props.setShopTypeTtlSeconds(0);
        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
