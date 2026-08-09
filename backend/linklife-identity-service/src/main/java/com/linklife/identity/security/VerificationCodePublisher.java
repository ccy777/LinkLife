package com.linklife.identity.security;

import com.linklife.common.core.config.RuntimeProfilePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 验证码受控输出：生产 profile 是发布器自身的最终防线（即使 production-validation-enabled=false 也不输出）；
 * 非生产且显式启用时输出开发验证码，手机号脱敏只保留尾号。
 */
@Slf4j
@Component
public class VerificationCodePublisher {

    private final Environment environment;

    public VerificationCodePublisher(Environment environment) {
        this.environment = environment;
    }

    /**
     * @return true = 已在允许的开发环境输出；false = 因生产环境等原因拒绝输出。
     */
    public boolean publishDevCode(String phone, String code) {
        if (RuntimeProfilePolicy.isProductionProfile(environment)) {
            // 生产最终防线：即使外围校验被跳过，也绝不输出手机号或验证码
            log.warn("检测到生产环境的验证码开发输出请求，已拒绝");
            return false;
        }
        log.info("[开发模式] 登录验证码（仅限非生产控制台输出）phone={}, code={}",
                SensitiveDataMasker.maskPhone(phone), code);
        return true;
    }
}
