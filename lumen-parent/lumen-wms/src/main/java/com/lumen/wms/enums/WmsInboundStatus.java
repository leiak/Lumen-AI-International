package com.lumen.wms.enums;

import lombok.Getter;

/**
 * 入库单状态。
 *
 * <p>状态流转：{@link #DRAFT} → {@link #PENDING_APPROVAL} → {@link #RECEIVING}
 * → {@link #PUTAWAY} → {@link #DONE}；任意阶段可取消为 {@link #CANCELLED}。
 *
 * <p>数据库字段 {@code wms_inbound_note.status} 使用枚举 {@link #code} 值持久化。
 */
@Getter
public enum WmsInboundStatus {

    DRAFT("DRAFT", "草稿"),
    PENDING_APPROVAL("PENDING_APPROVAL", "待审批"),
    RECEIVING("RECEIVING", "收货中"),
    PUTAWAY("PUTAWAY", "上架中"),
    DONE("DONE", "已完成"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    WmsInboundStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据数据库字符串 code 解析为枚举常量；未匹配时抛出异常。
     */
    public static WmsInboundStatus fromCode(String code) {
        for (WmsInboundStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown WmsInboundStatus code: " + code);
    }

    /**
     * 终态判断：{@link #DONE} 与 {@link #CANCELLED} 之后不再允许业务流转。
     */
    public boolean isTerminal() {
        return this == DONE || this == CANCELLED;
    }
}