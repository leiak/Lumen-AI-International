package com.lumen.dispatch.service;

import com.lumen.common.api.PageResult;
import com.lumen.dispatch.dto.VehicleDtos.CreateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.QueryVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.UpdateVehicleRequest;
import com.lumen.dispatch.dto.VehicleDtos.VehicleVO;
import com.lumen.dispatch.entity.WmsVehicle;

/**
 * 车辆主数据服务.
 *
 * <p>状态机相关操作见 spec §4：VEHICLE 状态机 IDLE/DISPATCHED/LOADING/
 * ENROUTE/RETURNING/OUT 共 6 状态 + 10 transition (含 5 个 'out' 任意→OUT).
 * PR3 仅暴露 {@link #freeze(Long)} / {@link #unfreeze(Long)} 两个用户面
 * 操作；其它 transition 由后续 PR4 的 DeliveryOrderService 在派车 / 装载 /
 * 出发 / 到达 / 回程 等场景触发.
 *
 * <p>实现见 {@link com.lumen.dispatch.service.impl.VehicleServiceImpl}.
 */
public interface VehicleService {

    /**
     * 分页查询车辆.
     *
     * @param current 页号 (从 1 开始)
     * @param size    每页大小
     * @param query   可选过滤条件 (status / type / plateNo)
     */
    PageResult<WmsVehicle> page(long current, long size, QueryVehicleRequest query);

    /**
     * 按主键查询车辆，未找到抛出 {@code VehicleNotFoundException}.
     */
    WmsVehicle get(Long id);

    /**
     * 新建车辆. 车牌号唯一约束冲突时抛 {@code VehicleDuplicatePlateException}.
     */
    WmsVehicle create(CreateVehicleRequest req);

    /**
     * 更新车辆. 车牌号唯一约束冲突时抛 {@code VehicleDuplicatePlateException}.
     */
    WmsVehicle update(Long id, UpdateVehicleRequest req);

    /**
     * 删除车辆（逻辑删，由 MyBatis-Plus 逻辑删除字段处理）.
     */
    void delete(Long id);

    /**
     * 冻结车辆 (IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING → OUT).
     *
     * <p>调用 {@link com.lumen.extension.state.StateMachineEngine#assertTransition}
     * 校验 V20 中 'out' transition 合法后置 {@code status='OUT'}.
     */
    WmsVehicle freeze(Long id);

    /**
     * 解冻车辆 (OUT → IDLE).
     *
     * <p>V20 没有 seed OUT → IDLE 的 transition，因此本方法
     * <strong>绕过</strong>状态机直接 setStatus('IDLE'). 此为运营兜底动作，
     * 由 {@code dispatch_admin} 角色手动操作.
     */
    WmsVehicle unfreeze(Long id);

    /**
     * 实体转视图对象.
     */
    VehicleVO toVO(WmsVehicle entity);
}