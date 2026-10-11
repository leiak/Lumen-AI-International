package com.lumen.wms.approval;

import com.lumen.wms.WmsConstants;
import com.lumen.wms.entity.WmsThresholdConfig;
import com.lumen.wms.service.ThresholdConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 阈值评估器 — 根据租户配置判断业务单据是否需要上级审批.
 *
 * <p>策略: 当 {@code approvalRequiredWhenExceed == 1} 时,任一阈值(金额/数量)被超过
 * 即视为需审批. 阈值为 0 或负数时,视为该维度不限制.
 *
 * <p>本类是纯函数式组件 — 无状态,可被 {@code InboundService} /
 * {@code OutboundService} / {@code InventoryAdjustService} 等任意业务流复用.
 */
@Component
@RequiredArgsConstructor
public class ThresholdEvaluator {

    private final ThresholdConfigService cfgSvc;

    /**
     * 判断给定业务单据是否需要进入审批流.
     *
     * @param bizKind  {@link WmsConstants#BIZ_INBOUND} / {@link WmsConstants#BIZ_OUTBOUND} / {@link WmsConstants#BIZ_ADJUST}
     * @param amount   单据总金额(可为 null → 视为 0)
     * @param totalQty 单据总数量(可为 null → 视为 0)
     * @return true 表示需要审批;false 表示免审直入下一节点
     */
    public boolean requiresApproval(String bizKind, BigDecimal amount, BigDecimal totalQty) {
        WmsThresholdConfig c = cfgSvc.getOrInit();
        // 1 = 启用;0 或 null 均视为关闭闸门
        if (c.getApprovalRequiredWhenExceed() == null || c.getApprovalRequiredWhenExceed() != 1) {
            return false;
        }
        BigDecimal amtThr = pick(c, bizKind, "amt");
        BigDecimal qtyThr = pick(c, bizKind, "qty");
        boolean amtOver = amtThr.signum() > 0 && safeCompare(amount, amtThr) > 0;
        boolean qtyOver = qtyThr.signum() > 0 && safeCompare(totalQty, qtyThr) > 0;
        return amtOver || qtyOver;
    }

    private static BigDecimal pick(WmsThresholdConfig c, String kind, String what) {
        return switch (kind + ":" + what) {
            case WmsConstants.BIZ_INBOUND  + ":amt" -> nz(c.getInbAmountThreshold());
            case WmsConstants.BIZ_INBOUND  + ":qty" -> nz(c.getInbQtyThreshold());
            case WmsConstants.BIZ_OUTBOUND + ":amt" -> nz(c.getOutAmountThreshold());
            case WmsConstants.BIZ_OUTBOUND + ":qty" -> nz(c.getOutQtyThreshold());
            case WmsConstants.BIZ_ADJUST   + ":amt" -> nz(c.getAdjAmountThreshold());
            case WmsConstants.BIZ_ADJUST   + ":qty" -> nz(c.getAdjQtyThreshold());
            default -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int safeCompare(BigDecimal a, BigDecimal b) {
        return a == null ? -1 : a.compareTo(b);
    }
}
