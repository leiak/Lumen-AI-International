package com.lumen.wms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * WMS 审批阈值配置（每个租户一行）.
 *
 * <p>按业务金额/数量阈值判断后续单据是否需要上级审批；
 * {@code approvalRequiredWhenExceed} 控制启用开关.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_threshold_config")
public class WmsThresholdConfig extends BaseEntity {

    private BigDecimal inbAmountThreshold;

    private BigDecimal inbQtyThreshold;

    private BigDecimal outAmountThreshold;

    private BigDecimal outQtyThreshold;

    private BigDecimal adjAmountThreshold;

    private BigDecimal adjQtyThreshold;

    private Integer approvalRequiredWhenExceed;
}
