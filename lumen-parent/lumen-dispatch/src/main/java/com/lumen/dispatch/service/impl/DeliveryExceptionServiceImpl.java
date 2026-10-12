package com.lumen.dispatch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.tenant.TenantContext;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.CreateDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.InvestigateRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.QueryDeliveryExceptionRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ReopenRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.ResolveRequest;
import com.lumen.dispatch.dto.DeliveryExceptionDtos.UpdateDeliveryExceptionRequest;
import com.lumen.dispatch.entity.WmsDeliveryException;
import com.lumen.dispatch.event.DeliveryExceptionStateChangedEvent;
import com.lumen.dispatch.exception.DeliveryExceptionNotFoundException;
import com.lumen.dispatch.mapper.WmsDeliveryExceptionMapper;
import com.lumen.dispatch.service.DeliveryExceptionService;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.state.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 异常工单服务实现 (Iteration 4.1 PR4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryExceptionServiceImpl implements DeliveryExceptionService {

    private final WmsDeliveryExceptionMapper mapper;
    private final StateMachineEngine stateMachineEngine;
    private final EventBus eventBus;

    @Override
    public PageResult<WmsDeliveryException> page(long current, long size, QueryDeliveryExceptionRequest query) {
        Page<WmsDeliveryException> p = new Page<>(current, size);
        LambdaQueryWrapper<WmsDeliveryException> qw = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getStatus() != null && !query.getStatus().isBlank()) {
                qw.eq(WmsDeliveryException::getStatus, query.getStatus());
            }
            if (query.getType() != null && !query.getType().isBlank()) {
                qw.eq(WmsDeliveryException::getType, query.getType());
            }
            if (query.getOrderId() != null) {
                qw.eq(WmsDeliveryException::getOrderId, query.getOrderId());
            }
        }
        qw.orderByDesc(WmsDeliveryException::getId);
        Page<WmsDeliveryException> res = mapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), current, size);
    }

    @Override
    public WmsDeliveryException get(Long id) {
        WmsDeliveryException e = mapper.selectById(id);
        if (e == null) throw new DeliveryExceptionNotFoundException(id);
        return e;
    }

    @Override
    @Transactional
    public WmsDeliveryException create(CreateDeliveryExceptionRequest req) {
        WmsDeliveryException entity = new WmsDeliveryException();
        BeanUtils.copyProperties(req, entity);
        if (entity.getTenantId() == null) {
            entity.setTenantId(TenantContext.require());
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(DispatchConstants.EX_OPEN);
        }
        mapper.insert(entity);
        log.info("Delivery exception {} created: order={} type={}",
                entity.getId(), entity.getOrderId(), entity.getType());
        return entity;
    }

    @Override
    @Transactional
    public WmsDeliveryException update(Long id, UpdateDeliveryExceptionRequest req) {
        WmsDeliveryException existing = get(id);
        BeanUtils.copyProperties(req, existing,
                "id", "tenantId", "createdAt", "orderId", "type", "status", "resolvedAt");
        mapper.updateById(existing);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryException investigate(Long id, InvestigateRequest req) {
        WmsDeliveryException e = get(id);
        String from = e.getStatus();
        String to = DispatchConstants.EX_INVESTIGATING;
        stateMachineEngine.assertTransition(DispatchConstants.SM_EXCEPTION_WORK_ORDER, from, to);

        e.setStatus(to);
        e.setHandler(req.getHandler());
        mapper.updateById(e);

        // PR2: 发布"开始调查"事件 (OPEN → INVESTIGATING).
        eventBus.publish(DeliveryExceptionStateChangedEvent.of(e, from, to, "investigate"));

        log.info("Delivery exception {} investigated: handler={} {} -> {}",
                id, req.getHandler(), from, to);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryException resolve(Long id, ResolveRequest req) {
        WmsDeliveryException e = get(id);
        String from = e.getStatus();
        String to = DispatchConstants.EX_RESOLVED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_EXCEPTION_WORK_ORDER, from, to);

        e.setStatus(to);
        e.setResolvedAt(LocalDateTime.now());
        if (req.getResolutionNotes() != null) {
            e.setDescription(
                    (e.getDescription() == null ? "" : e.getDescription() + "\n--- RESOLUTION ---\n")
                            + req.getResolutionNotes());
        }
        mapper.updateById(e);

        // PR2: 发布"解决"事件 (INVESTIGATING → RESOLVED).
        eventBus.publish(DeliveryExceptionStateChangedEvent.of(e, from, to, "resolve"));

        log.info("Delivery exception {} resolved: {} -> {}", id, from, to);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryException close(Long id) {
        WmsDeliveryException e = get(id);
        String from = e.getStatus();
        String to = DispatchConstants.EX_CLOSED;
        stateMachineEngine.assertTransition(DispatchConstants.SM_EXCEPTION_WORK_ORDER, from, to);

        e.setStatus(to);
        mapper.updateById(e);

        // PR2: 发布"关闭"事件 (RESOLVED → CLOSED).
        eventBus.publish(DeliveryExceptionStateChangedEvent.of(e, from, to, "close"));

        log.info("Delivery exception {} closed: {} -> {}", id, from, to);
        return mapper.selectById(id);
    }

    @Override
    @Transactional
    public WmsDeliveryException reopen(Long id, ReopenRequest req) {
        WmsDeliveryException e = get(id);
        String from = e.getStatus();
        // V20 状态机定义：reopen = CLOSED → OPEN（不是 CLOSED → INVESTIGATING）。
        // 调用方需要在 reopen 之后显式调用 investigate() 来重新进入调查流程。
        String to = DispatchConstants.EX_OPEN;
        stateMachineEngine.assertTransition(DispatchConstants.SM_EXCEPTION_WORK_ORDER, from, to);

        // 用 UpdateWrapper 一次性写多列：status / resolved_at=NULL / description。
        // 实体上 resolvedAt 已加 @TableField(updateStrategy=IGNORED), updateById
        // 现在也能写 NULL, 但 UpdateWrapper 更显式也更便于单测断言.
        UpdateWrapper<WmsDeliveryException> uw = new UpdateWrapper<>();
        uw.eq("id", id)
          .set("status", to)
          .set("resolved_at", null);
        if (req.getReason() != null) {
            String newDescription = (e.getDescription() == null ? "" : e.getDescription() + "\n--- REOPENED ---\n")
                    + req.getReason();
            uw.set("description", newDescription);
            // PR2: 在内存中同步更新 description, 让 outbox payload 与 DB 一致
            e.setDescription(newDescription);
        }
        mapper.update(null, uw);
        // PR2: in-memory 同步 status + resolvedAt (UpdateWrapper 不动实体字段,
        // outbox payload 需要反映"重开后"的真实状态).
        e.setStatus(to);
        e.setResolvedAt(null);

        // PR2: 发布"重开"事件 (CLOSED → OPEN, V20).
        eventBus.publish(DeliveryExceptionStateChangedEvent.of(e, from, to, "reopen"));

        log.info("Delivery exception {} reopened: {} -> {}", id, from, to);
        return mapper.selectById(id);
    }
}
