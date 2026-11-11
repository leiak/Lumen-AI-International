package com.lumen.order.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 委托单详情 (Iteration 4.6).
 *
 * <p>{@code allowedNextStates} = {@code StateMachineEngine.allowedNext("ORDER", 当前态)},
 * 前端据此渲染可执行动作按钮 —— 这是本轮唯一一处「表驱动状态机反向驱动 UI」的地方。
 * <br><b>门户端例外</b>: 门户视图会把这一列收窄成「客户自己点得到的」那一组
 * (见 {@code PortalFreightOrderController.portalNextStates}), 管理端才是整张状态机的出边。
 *
 * <p><b>由 {@code OrderServiceImpl.toDetail} 计算</b> —— 该实现已注入
 * {@code StateMachineEngine}, 走 {@code stateMachineEngine.allowedNext(
 * OrderConstants.STATE_MACHINE_CODE, orderStatus)}; 引擎返回 null 时兜底成
 * {@code List.of()} (见 {@code OrderServiceImpl.allowedNextStates})。
 */
public record OrderDetailView(
        Long id,
        String orderNo,
        Long customerId,
        String businessType,
        String tradeDirection,
        String incoterms,
        String serviceType,
        String originAddress,
        String destAddress,
        BigDecimal totalQty,
        BigDecimal totalWeightKg,
        BigDecimal totalVolumeCbm,
        String orderStatus,
        String settlementCurrency,
        LocalDate etd,
        LocalDate eta,
        LocalDateTime createdAt,
        LocalDate cargoReadyDate,
        Integer containerQty,
        String containerTypeCode,
        String marksAndNos,
        String cargoDescription,
        Integer isDangerous,
        Integer isOversized,
        String temperatureRange,
        String remark,
        String cancelReason,
        String closeReason,
        String voidReason,
        LocalDateTime submittedAt,
        LocalDateTime confirmedAt,
        LocalDateTime deliveredAt,
        LocalDateTime settledAt,
        LocalDateTime closedAt,
        Long createdBy,
        Long updatedBy,
        List<CargoView> cargo,
        List<ServiceItemView> serviceItems,
        List<String> allowedNextStates
) {

    /**
     * 返回一份换掉服务项的副本 —— 其余 38 个组件逐个原样保留。
     * 门户端用它把内部成本从服务项里摘掉 (见 ServiceItemView#withoutInternalCost)。
     */
    public OrderDetailView withServiceItems(List<ServiceItemView> newServiceItems) {
        return new OrderDetailView(id, orderNo, customerId, businessType, tradeDirection,
                incoterms, serviceType, originAddress, destAddress, totalQty, totalWeightKg,
                totalVolumeCbm, orderStatus, settlementCurrency, etd, eta, createdAt,
                cargoReadyDate, containerQty, containerTypeCode, marksAndNos, cargoDescription,
                isDangerous, isOversized, temperatureRange, remark, cancelReason, closeReason,
                voidReason, submittedAt, confirmedAt, deliveredAt, settledAt, closedAt,
                createdBy, updatedBy, cargo, newServiceItems, allowedNextStates);
    }

    /**
     * 返回一份换掉 allowedNextStates 的副本 —— 其余 38 个组件逐个原样保留。
     *
     * <p><b>门户端用它裁掉客户看不到的内部状态</b> —— 管理端的 allowedNextStates
     * 是整张状态机的出边 (含 VOID / IN_OPERATION / SETTLED / CLOSED 这些内部动作),
     * 直接返给客户等于把我方内部流转暴露出去, 而且已确认的单会因为
     * 「CONFIRMED → CANCELLED 是主管动作」而多出一个「取消」按钮, 客户一点必撞 409。
     */
    public OrderDetailView withAllowedNextStates(List<String> newAllowedNextStates) {
        return new OrderDetailView(id, orderNo, customerId, businessType, tradeDirection,
                incoterms, serviceType, originAddress, destAddress, totalQty, totalWeightKg,
                totalVolumeCbm, orderStatus, settlementCurrency, etd, eta, createdAt,
                cargoReadyDate, containerQty, containerTypeCode, marksAndNos, cargoDescription,
                isDangerous, isOversized, temperatureRange, remark, cancelReason, closeReason,
                voidReason, submittedAt, confirmedAt, deliveredAt, settledAt, closedAt,
                createdBy, updatedBy, cargo, serviceItems, newAllowedNextStates);
    }
}
