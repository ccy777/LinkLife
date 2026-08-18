package com.linklife.merchant.geo;

import com.linklife.common.core.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Final-Audit-R2-A: GEO coordinate boundary contract.
 */
class ShopGeoCoordinateValidatorTest {

    private final ShopGeoCoordinateValidator validator = new ShopGeoCoordinateValidator();

    @Test
    void validCoordinatesPass() {
        assertThatCode(() -> validator.requireValid(120.149192, 30.316078)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireValid(-180.0, -85.05112878)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireValid(180.0, 85.05112878)).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireValid(0.0, 0.0)).doesNotThrowAnyException();
    }

    @Test
    void nullCoordinatesRejected() {
        assertThatThrownBy(() -> validator.requireValid(null, 30.0))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商铺坐标不合法");
        assertThatThrownBy(() -> validator.requireValid(120.0, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void longitudeOutOfRangeRejected() {
        assertThatThrownBy(() -> validator.requireValid(181.0, 30.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(-181.0, 30.0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void latitudeOutOfRangeRejected() {
        assertThatThrownBy(() -> validator.requireValid(120.0, 90.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(120.0, -90.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(120.0, 85.05112879))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void nanAndInfinityRejected() {
        assertThatThrownBy(() -> validator.requireValid(Double.NaN, 30.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(120.0, Double.NaN))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(Double.POSITIVE_INFINITY, 30.0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validator.requireValid(120.0, Double.NEGATIVE_INFINITY))
                .isInstanceOf(BusinessException.class);
    }
}
