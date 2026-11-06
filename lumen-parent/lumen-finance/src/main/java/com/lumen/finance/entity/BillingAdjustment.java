package com.lumen.finance.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_billing_adjustment")
public class BillingAdjustment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String adjustmentNo;
    private Long sourceInvoiceId;
    private String adjustmentType; // CREDIT / DEBIT
    private BigDecimal amount;
    private String currency;
    private BigDecimal fxRateAtCreation;
    private BigDecimal taxRate;
    private BigDecimal grossAmount;
    private BigDecimal taxAmount;
    private BigDecimal netAmount;
    private String reason;
    private String status;
    private Long l1ApproverId;
    private LocalDateTime l1ApprovedAt;
    private String l1Comment;
    private Long l2ApproverId;
    private LocalDateTime l2ApprovedAt;
    private String l2Comment;
    private String errorMsg;
    // 4.3.2 PR4 — createdAt/updatedAt 由 MyMetaObjectHandler 自动填 (与 BillingInvoice 对齐)
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private LocalDateTime updatedAt;
    // 注意: V29 schema 没有 version / deleted 列 (调账单支持硬删除或 append-only 审计),
    // 故意不加 @Version / @TableLogic, 与 t_billing_invoice 不同.

    // ====== 4.5C-2.1 — 商家异议快照 (瞬态, 不落库) ======
    // 商家提异议后, 原话写在 t_reconciliation 的 dispute_reason / dispute_amount /
    // dispute_at 三列 (权威来源)。admin 侧审批界面要看到商家在争什么, 但调账单
    // 表本身没有这三列 —— V29 schema 是冻结的, 本切片不新增迁移。
    // 所以这里做成 @TableField(exist = false) 的瞬态字段: 由
    // AdjustmentServiceImpl.list / get 按 adjustment_id 反查 t_reconciliation 回填。
    // exist = false → MyBatis-Plus 不映射到任何列, insert / update 都不碰它。
    //
    // <b>契约</b>: 这三个字段**只有** AdjustmentService.list() / get() 的返回值会有值;
    // 写方法的返回值 (create / submit / l1Approve / ...) 不带 —— 快照是读路径的展示
    // 增强, 不是实体的固有属性。同一个类型承担了「持久化实体」和「带快照的视图」
    // 两个角色, 调用方别默认实体字段总是有值。
    @TableField(exist = false) private String disputeReason;
    @TableField(exist = false) private BigDecimal disputeAmount;
    @TableField(exist = false) private LocalDateTime disputeAt;
}