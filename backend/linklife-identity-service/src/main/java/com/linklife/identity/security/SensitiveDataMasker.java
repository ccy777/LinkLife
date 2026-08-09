package com.linklife.identity.security;

/**
 * 敏感数据脱敏：手机号只保留后 4 位；null 返回稳定占位符；不抛异常。
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {
    }

    public static String maskPhone(String phone) {
        if (phone == null) {
            return "<null>";
        }
        if (phone.length() <= 4) {
            return phone.replaceAll(".", "*");
        }
        return "*******" + phone.substring(phone.length() - 4);
    }
}
