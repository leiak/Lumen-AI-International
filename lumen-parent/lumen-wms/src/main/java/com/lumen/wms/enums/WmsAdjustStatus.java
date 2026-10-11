package com.lumen.wms.enums;

import lombok.Getter;

/**
 * 库存调整单状态。
 *
 * <p>状态流转：{@link #DRAFT} → {@link #PENDING_APPROVAL} → {@link #APPROVED}
 * → {@link #DONE}；审批可被驳回为 {@link #REJECTED}；任意阶段可取消为 {@link #CANCELLED}。
 *
 * <p>数据库字段 {@code wms_inventory_adjust.status} 使用枚举 {@link #code} 值持久化。
 */
@Getter
public enum WmsAdjustStatus {

    DRAFT("DRAFT", "草稿"),
    PENDING_APPROVAL("PENDING_APPROVAL", "待审批"),
    APPROVED("APPROVED", "已审批"),
    DONE("DONE", "已完成"),
    REJECTED("REJECTED", "已驳回"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    WmsAdjustStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据数据库字符串 code 解析为枚举常量；未匹配时抛出异常。
     */
    public static WmsAdjustStatus fromCode(String code) {
        for (WmsAdjustStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown WmsAdjustStatus code: " + code);
    }

    /**
     * 终态判断：{@link #DONE}、{@link #REJECTED} 与 {@link #CANCELLED} 之后不再允许业务流转。
     */
    public boolean isTerminal() {
        return this == DONE || this == REJECTED || this == CANCELLED;
    }
}