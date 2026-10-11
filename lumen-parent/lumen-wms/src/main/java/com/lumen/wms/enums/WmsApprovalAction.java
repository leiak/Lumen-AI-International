package com.lumen.wms.enums;

import lombok.Getter;

/**
 * WMS 统一审批/动作枚举。
 *
 * <p>用于 ThresholdEvaluator 与 Inbound/Outbound/Adjust 服务中 {@code action} 参数的语义对齐，
 * 对应 REST 接口：{@code /api/v1/inbound/{id}/submit}、{@code /api/v1/inbound/{id}/approve}
 * 等接口的 {@code action} 字段。
 *
 * <p>动作到状态机的映射见 design spec：
 * <ul>
 *   <li>{@link #SUBMIT} — DRAFT → PENDING_APPROVAL（进入审批流）</li>
 *   <li>{@link #SUBMIT_NO_THRESHOLD} — DRAFT → 直接进入下一业务态，跳过审批</li>
 *   <li>{@link #APPROVE} — PENDING_APPROVAL → 业务下一态</li>
 *   <li>{@link #REJECT} — PENDING_APPROVAL → REJECTED</li>
 *   <li>{@link #PUTAWAY} — RECEIVING → PUTAWAY（入库上架）</li>
 *   <li>{@link #COMPLETE} — PUTAWAY → DONE（入库完成）</li>
 *   <li>{@link #RECEIVE} — 入库服务从 DRAFT/免审批直接置为 RECEIVING</li>
 *   <li>{@link #REVIEW} — PICKING → REVIEW（出库复核）</li>
 *   <li>{@link #SHIP} — REVIEW → SHIPPED（出库发货）</li>
 *   <li>{@link #EXECUTE} — APPROVED → DONE（调整执行）</li>
 *   <li>{@link #FREEZE} — ACTIVE → FROZEN（库位冻结）</li>
 *   <li>{@link #UNFREEZE} — FROZEN → ACTIVE（库位解冻）</li>
 *   <li>{@link #DISABLE} — 库位停用</li>
 *   <li>{@link #CANCEL} — 任意可取消态 → CANCELLED</li>
 * </ul>
 */
@Getter
public enum WmsApprovalAction {

    SUBMIT("SUBMIT", "提交"),
    APPROVE("APPROVE", "审批通过"),
    REJECT("REJECT", "驳回"),
    PUTAWAY("PUTAWAY", "上架"),
    COMPLETE("COMPLETE", "完成"),
    CANCEL("CANCEL", "取消"),
    RECEIVE("RECEIVE", "收货"),
    REVIEW("REVIEW", "复核"),
    SHIP("SHIP", "发货"),
    EXECUTE("EXECUTE", "执行"),
    FREEZE("FREEZE", "冻结"),
    UNFREEZE("UNFREEZE", "解冻"),
    DISABLE("DISABLE", "停用"),
    SUBMIT_NO_THRESHOLD("SUBMIT_NO_THRESHOLD", "免审批提交");

    private final String code;
    private final String desc;

    WmsApprovalAction(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 根据数据库字符串 code 解析为枚举常量；未匹配时抛出异常。
     */
    public static WmsApprovalAction fromCode(String code) {
        for (WmsApprovalAction a : values()) {
            if (a.code.equals(code)) {
                return a;
            }
        }
        throw new IllegalArgumentException("Unknown WmsApprovalAction code: " + code);
    }

    /**
     * 是否为提交类动作（可能触发阈值评估）。
     */
    public boolean isSubmit() {
        return this == SUBMIT || this == SUBMIT_NO_THRESHOLD;
    }

    /**
     * 是否为审批动作。
     */
    public boolean isApprovalDecision() {
        return this == APPROVE || this == REJECT;
    }
}