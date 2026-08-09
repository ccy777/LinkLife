package com.linklife.common.core.contract;

/**
 * Stage 4 服务名契约：必须与各服务 spring.application.name 及 Nacos 注册名完全一致。
 */
public final class ServiceNames {

    public static final String GATEWAY = "linklife-gateway";
    public static final String IDENTITY = "linklife-identity-service";
    public static final String MERCHANT = "linklife-merchant-service";
    public static final String TRANSACTION = "linklife-transaction-service";
    public static final String SOCIAL = "linklife-social-service";

    private ServiceNames() {
    }
}
