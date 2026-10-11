package com.lumen.wms.enums;

import lombok.Getter;

/**
 * 库位状态。
 *
 * <p>{@link #ACTIVE} 可用；{@link #FROZEN} 冻结（不可上新业务但保留库存）；
 * {@link #DISABLED} 停用（彻底停摆，不可使用）。
 *
 * <p>数据库字段 {@code wms_location.status} 使用枚举 {@link #code} 值持久化。
 */
@Getter
public enum WmsLocationStatus {

    ACTIVE("ACTIVE", "可用"),
    FROZEN("FROZEN", "已冻结"),
    DISABLED("DISABLED", "已停用");

    private final String code;
    private final String desc;

    WmsLocationStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据数据库字符串 code 解析为枚举常量；未匹配时抛出异常。
     */
    public static WmsLocationStatus fromCode(String code) {
        for (WmsLocationStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown WmsLocationStatus code: " + code);
    }

    /**
     * 库位是否可用于分配（新业务落到该库位）。
     */
    public boolean isUsable() {
        return this == ACTIVE;
    }

    /**
     * 终态判断：{@link #DISABLED} 之后不再允许业务流转。
     */
    public boolean isTerminal() {
        return this == DISABLED;
    }
}