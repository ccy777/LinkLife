package com.linklife.merchant.config;

import com.linklife.merchant.geo.ShopGeoIndexService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Rebuilds {@code merchant:shop:geo:*} from MySQL at startup
 * (Final-Audit-R1-B). A rebuild failure propagates and fails application
 * startup: the service never silently declares a stale/empty GEO index ready.
 */
@Component
public class ShopGeoStartupRebuildRunner implements ApplicationRunner {

    private final ShopGeoIndexService shopGeoIndexService;

    public ShopGeoStartupRebuildRunner(ShopGeoIndexService shopGeoIndexService) {
        this.shopGeoIndexService = shopGeoIndexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        shopGeoIndexService.rebuildFromDatabase();
    }
}
