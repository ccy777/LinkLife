package com.linklife.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运行时安全开关（Stage 3 shared.config.RuntimeSecurityProperties 迁移至 Identity）。
 */
@Component
@ConfigurationProperties(prefix = "linklife.runtime")
public class RuntimeSecurityProperties {

    private boolean productionValidationEnabled = true;
    private boolean consoleVerificationCodeEnabled = false;

    public boolean isProductionValidationEnabled() {
        return productionValidationEnabled;
    }

    public void setProductionValidationEnabled(boolean productionValidationEnabled) {
        this.productionValidationEnabled = productionValidationEnabled;
    }

    public boolean isConsoleVerificationCodeEnabled() {
        return consoleVerificationCodeEnabled;
    }

    public void setConsoleVerificationCodeEnabled(boolean consoleVerificationCodeEnabled) {
        this.consoleVerificationCodeEnabled = consoleVerificationCodeEnabled;
    }
}
