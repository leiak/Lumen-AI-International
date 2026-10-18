package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 签收凭证 (Proof of Delivery) — 4.5A.3 T3.2 拆出独立表.
 *
 * <p>此前 4.5A.2 阶段 POD 数据存在 {@code wms_delivery_order.sign_photo_url} (V19);
 * V35 migration 拆出本表, 承载 PHOTO / SIGNATURE / NONE 三类凭证. 一个 do 最多一条
 * 活跃 POD 行 (uk_wms_pod_do 唯一键: do_id + deleted).
 *
 * <p>{@code tenant_id} 由调用方注入; {@code created_at / updated_at / version /
 * deleted} 由 MyBatis-Plus 自动填充 / 维护 (@see {@link BaseEntity}).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_delivery_pod")
public class Pod extends BaseEntity {

    /**
     * 关联配送单 ID (FK {@code wms_delivery_order.id}, V35 schema 中列名 {@code do_id}).
     */
    private Long doId;

    /**
     * POD 类型: {@code PHOTO} / {@code SIGNATURE} / {@code NONE}.
     */
    private String podType;

    /**
     * 签收照片 URL (相对路径). {@code podType=PHOTO} 时非空.
     */
    private String imageUrl;

    /**
     * 手写签名 base64. {@code podType=SIGNATURE} 时非空; MEDIUMTEXT 列存储.
     */
    private String signatureBase64;

    /**
     * 签收人姓名.
     */
    private String signerName;

    /**
     * 签收时间. 来源: V19 中 {@code wms_delivery_order.delivered_at} (已签收时);
     * V35 backfill 将其复制为 {@code signed_at}.
     */
    private LocalDateTime signedAt;
}
