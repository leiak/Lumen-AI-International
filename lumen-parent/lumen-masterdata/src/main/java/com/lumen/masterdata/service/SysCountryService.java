package com.lumen.masterdata.service;

import com.lumen.common.api.PageResult;
import com.lumen.masterdata.entity.SysCountry;

public interface SysCountryService {
    PageResult<SysCountry> page(long pageNum, long pageSize, String keyword);
    SysCountry create(SysCountry entity);
    SysCountry update(Long id, SysCountry entity);
    SysCountry getById(Long id);
    void delete(Long id);

    /**
     * 修改国家状态。流程：
     * <ol>
     *   <li>查 {@code SysCountry}（不存在抛 {@code BizException(404)}）。</li>
     *   <li>若 {@code oldState == newState} 直接返回（幂等）。</li>
     *   <li>调用 {@code StateMachineEngine.assertTransition("sys_country", oldState, newState)}
     *       —— 不允许的转换抛 {@code StateMachineException(400)}。</li>
     *   <li>乐观锁更新 status 字段；冲突抛 {@code BizException(409)}。</li>
     *   <li>发布 {@link com.lumen.extension.outbox.events.CountryStateChangedEvent}，
     *       走 outbox + dispatcher，事务内同步 publishEvent + 跨进程异步分发。</li>
     * </ol>
     *
     * @param countryId  国家 ID
     * @param newState   目标状态（DRAFT / ACTIVE / FROZEN / VOID）
     * @param operatorId 操作人用户 ID（写入事件审计字段）
     * @return 更新后的 SysCountry
     */
    SysCountry changeState(Long countryId, String newState, Long operatorId);
}