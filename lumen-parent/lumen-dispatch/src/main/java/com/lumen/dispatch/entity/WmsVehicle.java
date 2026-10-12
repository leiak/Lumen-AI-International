package com.lumen.dispatch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lumen.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 车辆主数据 (Vehicle master).
 *   max_load_kg   > 0  (DB-level constraint)
 *   max_volume_m3 > 0  (DB-level constraint)
 *   status        VEHICLE 状态机: IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING/OUT (PR2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wms_vehicle")
public class WmsVehicle extends BaseEntity {

    private String plateNo;

    private String type;

    private BigDecimal maxLoadKg;

    private BigDecimal maxVolumeM3;

    private String status;

    private Long driverId;

    private Long carrierId;

    private String remark;
}