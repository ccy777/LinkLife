package com.linklife.merchant.geo;

import com.linklife.common.core.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * Shop GEO coordinate boundary validator (Final-Audit-R2-A).
 *
 * <p>Redis GEOADD accepts longitude in [-180, 180] and latitude in
 * [-85.05112878, 85.05112878]; anything outside that range raises a Redis
 * error. This validator rejects such coordinates up front so invalid values
 * never reach MySQL (create/update) and never turn a Redis GEOSEARCH error
 * into a 500 (nearby query).
 */
@Component
public class ShopGeoCoordinateValidator {

    public static final double MIN_LONGITUDE = -180.0;
    public static final double MAX_LONGITUDE = 180.0;
    public static final double MIN_LATITUDE = -85.05112878;
    public static final double MAX_LATITUDE = 85.05112878;

    private static final String INVALID_MESSAGE = "商铺坐标不合法";

    /**
     * Reject null, NaN, Infinity and out-of-range coordinates with a clear
     * business error (never a Redis exception).
     */
    public void requireValid(Double x, Double y) {
        if (x == null || y == null) {
            throw new BusinessException(INVALID_MESSAGE);
        }
        if (Double.isNaN(x) || Double.isInfinite(x)
                || Double.isNaN(y) || Double.isInfinite(y)) {
            throw new BusinessException(INVALID_MESSAGE);
        }
        if (x < MIN_LONGITUDE || x > MAX_LONGITUDE
                || y < MIN_LATITUDE || y > MAX_LATITUDE) {
            throw new BusinessException(INVALID_MESSAGE);
        }
    }
}
