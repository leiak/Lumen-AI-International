package com.lumen.dispatch.service;

import com.lumen.common.api.PageResult;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.CreateDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.InvestigateRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.QueryDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ReopenRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ResolveRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.UpdateDeliveryExceptionRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;

/**
 * 配送异常工单服务.
 *
 * <p>状态机（spec §4 / V20 seed EXCEPTION_WORK_ORDER）：
 * <pre>
 *   OPEN --investigate--&gt; INVESTIGATING --resolve--&gt; RESOLVED --close--&gt; CLOSED
 *                                              |
 *                                              +--reopen--&gt; INVESTIGATING (RESOLVED/CLOSED 也可)
 * </pre>
 */
public interface DeliveryExceptionService {

    /** 分页查询. */
    PageResult<WmsDeliveryException> page(long current, long size, QueryDeliveryExceptionRequest query);

    /** 主键查询（NotFound 抛 {@code DeliveryExceptionNotFoundException}). */
    WmsDeliveryException get(Long id);

    /** 新建 (默认 status=OPEN). */
    WmsDeliveryException create(CreateDeliveryExceptionRequest req);

    /** 更新可变更字段 (description / handler). */
    WmsDeliveryException update(Long id, UpdateDeliveryExceptionRequest req);

    /** OPEN → INVESTIGATING. */
    WmsDeliveryException investigate(Long id, InvestigateRequest req);

    /** INVESTIGATING → RESOLVED. */
    WmsDeliveryException resolve(Long id, ResolveRequest req);

    /** RESOLVED → CLOSED. */
    WmsDeliveryException close(Long id);

    /** RESOLVED/CLOSED → INVESTIGATING. */
    WmsDeliveryException reopen(Long id, ReopenRequest req);
}
