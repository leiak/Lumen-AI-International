package com.lumen.cs.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 只读视图 (Iteration 4.2 PR4): 映射 lumen-dispatch 的
 * {@code wms_delivery_exception} 表。lumen-cs 不持有该表的写入权
 * (任何修改走 lumen-dispatch service), 这里仅供 SLA 升级扫描器查询
 * OPEN / INVESTIGATING 工单.
 *
 * <p>只读语义:
 * <ul>
 *   <li>{@code @TableLogic} 自动过滤 {@code deleted=0} 的行</li>
 *   <li>不带 {@code @Version} (no writes; 不参与乐观锁)</li>
 *   <li>{@code @TableId(type = AUTO)} 与 dispatch 实体一致</li>
 * </ul>
 *
 * <p>注意: 该表由 lumen-dispatch 维护; 字段增删需同步 dispatch 的
 * {@code WmsDeliveryException} 实体和 V22__dispatch_exception.sql.
 */
@Data
@TableName("wms_delivery_exception")
public class WmsDeliveryExceptionView {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long orderId;
    /** DAMAGE / OVERTIME / REJECTED / ADDR_ERR. */
    private String type;
    /** OPEN / INVESTIGATING / RESOLVED / CLOSED / CANCELLED. */
    private String status;
    /** 处理人 username (e.g. "客服Alice", "cs_supervisor_3"). */
    private String handler;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    @TableLogic
    private Integer deleted;
}
