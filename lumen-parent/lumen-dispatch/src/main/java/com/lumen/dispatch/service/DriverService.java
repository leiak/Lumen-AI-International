package com.lumen.dispatch.service;

import com.lumen.common.api.PageResult;
import com.lumen.dispatch.dto.DriverDtos.CreateDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.DriverVO;
import com.lumen.dispatch.dto.DriverDtos.QueryDriverRequest;
import com.lumen.dispatch.dto.DriverDtos.UpdateDriverRequest;
import com.lumen.dispatch.entity.WmsDriver;

/**
 * 司机主数据服务.
 *
 * <p>状态机极简（DB CHECK 约束 ACTIVE/SUSPENDED 二选一），不走
 * {@code t_state_transition} 引擎，{@link #suspend(Long)} /
 * {@link #resume(Long)} 在 service 内 setStatus 直改.
 */
public interface DriverService {

    PageResult<WmsDriver> page(long current, long size, QueryDriverRequest query);

    WmsDriver get(Long id);

    WmsDriver create(CreateDriverRequest req);

    WmsDriver update(Long id, UpdateDriverRequest req);

    void delete(Long id);

    /** ACTIVE → SUSPENDED. */
    WmsDriver suspend(Long id);

    /** SUSPENDED → ACTIVE. */
    WmsDriver resume(Long id);

    DriverVO toVO(WmsDriver entity);
}