package com.lumen.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 货运委托单主表 {@code t_order} (Iteration 4.6).
 *
 * <p>继承 {@link BaseEntity} 拿到 id / tenantId / createdAt / updatedAt / deleted / version。
 * 注意 {@code tenantId} 是 {@code @TableField(fill = INSERT)} —— 插入时由
 * {@code MyMetaObjectHandler} 按 {@code TenantContext} 自动填; {@code deleted} 带 {@code @TableLogic},
 * MP 会自动补 {@code deleted = 0} 过滤。
 *
 * <p><b>列名无需逐列写 {@code @TableField}</b> —— MyBatis-Plus 构造出的
 * {@code MybatisConfiguration} 把 {@code mapUnderscoreToCamelCase} 置为 true,
 * 字段名按驼峰转下划线推导列名, 故 {@code isDangerous → is_dangerous}、
 * {@code unNo → un_no} 这类都能自动对上 (本仓既有实体 {@code WmsDeliveryOrder} 同此口径)。
 * 字段与列的一一对应由 {@code OrderEntityColumnMappingTest} 锁住。
 *
 * <p>{@code totalQty / totalWeightKg / totalVolumeCbm / isDangerous} 是**派生字段** ——
 * 由 {@code t_order_cargo} 聚合后回写, <b>service 层不应直接赋值</b>
 * (实体是纯 {@code @Data}, setter 照常生成, 代码层面拦不住外部写入)。
 *
 * <p>{@code createdBy / updatedBy} 是本表独有的审计列 —— {@code BaseEntity} 里没有这两个字段,
 * 而 {@code t_order_cargo} / {@code t_order_service_item} 两张明细表也没有这两列。
 * <b>这两个字段不会被自动填充</b> —— {@code MyMetaObjectHandler.insertFill} 只填
 * {@code createdAt / updatedAt / tenantId / deleted / version}, 不填 {@code createdBy / updatedBy};
 * 必须由 service 显式赋值, 否则永远是 NULL。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order")
public class Order extends BaseEntity {

    private String orderNo;
    private String businessType;
    /** wms_customer.id —— 无 FK。 */
    private Long customerId;
    private Long customerContractId;
    private Long salesId;
    private Long operatorId;
    private String tradeDirection;
    private String incoterms;
    private String serviceType;
    private Long originPortId;
    private Long destPortId;
    private String originAddress;
    private String destAddress;
    private Long pickupWarehouseId;
    private Long deliveryWarehouseId;
    private LocalDate cargoReadyDate;
    private LocalDate etd;
    private LocalDate eta;
    private BigDecimal totalQty;
    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeCbm;
    private Integer isDangerous;
    private Integer isOversized;
    private String temperatureRange;
    private Integer containerQty;
    private String containerTypeCode;
    private String marksAndNos;
    private String cargoDescription;
    private String orderStatus;
    private String settlementCurrency;
    private String exchangeRateType;
    private BigDecimal lockedExchangeRate;
    private Integer isLocked;
    private LocalDateTime lockAt;
    private Long lockBy;
    private Long inquiryId;
    private Long quotationId;
    private String cancelReason;
    private String closeReason;
    private String voidReason;
    private LocalDateTime submittedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime settledAt;
    private LocalDateTime closedAt;
    private String remark;
    /** 创建人 —— **不会被自动填充** (见类 javadoc), 须由 service 显式赋值。 */
    private Long createdBy;
    /** 最后修改人 —— **不会被自动填充** (见类 javadoc), 须由 service 显式赋值。 */
    private Long updatedBy;
}
