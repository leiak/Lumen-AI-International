package com.lumen.common.util;

public final class SensitiveMaskUtil {
    private SensitiveMaskUtil() {}

    public static String maskPassword(String raw) { return "******"; }

    public static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 7) return "***";
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }

    public static String maskToken(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
