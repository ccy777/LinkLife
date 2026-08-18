package com.linklife.transaction;

import com.linklife.common.web.context.UserContextFilter;
import com.linklife.common.web.exception.GlobalExceptionHandler;
import com.linklife.common.web.security.AdminAuthorizationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Transaction Service 启动类：promotion + trade 同进程同库。
 * scanBasePackages 显式覆盖 transaction/promotion/trade/shared（shared 仅本 module 独占内部件）；
 * common-web 共享配置显式 @Import。
 */
@SpringBootApplication(scanBasePackages = {
        "com.linklife.transaction",
        "com.linklife.promotion",
        "com.linklife.trade",
        "com.linklife.shared"
})
@MapperScan({"com.linklife.promotion.mapper", "com.linklife.trade.mapper"})
@Import({GlobalExceptionHandler.class, UserContextFilter.class, AdminAuthorizationProperties.class})
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
