package com.lumen.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.api.PageResult;
import com.lumen.common.error.BizException;
import com.lumen.common.error.ErrorCode;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.EventBus;
import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import com.lumen.extension.state.StateMachineEngine;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.mapper.SysCountryMapper;
import com.lumen.masterdata.service.SysCountryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysCountryServiceImpl implements SysCountryService {

    /** SysCountry 业务错误码：{@code 9001 = 国家不存在 (404)}，{@code 9002 = 乐观锁冲突 (409)}。 */
    private static final int CODE_COUNTRY_NOT_FOUND = 9001;
    private static final int CODE_COUNTRY_VERSION_CONFLICT = 9002;

    private final SysCountryMapper countryMapper;
    private final StateMachineEngine stateMachineEngine;
    private final EventBus eventBus;

    @Override
    public PageResult<SysCountry> page(long pageNum, long pageSize, String keyword) {
        Page<SysCountry> p = Page.of(pageNum, pageSize);
        QueryWrapper<SysCountry> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like("code", keyword).or().like("name_cn", keyword).or().like("name_en", keyword);
        Page<SysCountry> res = countryMapper.selectPage(p, qw);
        return PageResult.of(res.getRecords(), res.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional
    public SysCountry create(SysCountry entity) {
        if (entity.getTenantId() == null) entity.setTenantId(TenantContext.require());
        if (entity.getStatus() == null) entity.setStatus("DRAFT");
        countryMapper.insert(entity);
        return entity;
    }

    @Override
    public SysCountry update(Long id, SysCountry entity) {
        entity.setId(id);
        countryMapper.updateById(entity);
        return countryMapper.selectById(id);
    }

    @Override
    public SysCountry getById(Long id) { return countryMapper.selectById(id); }

    @Override
    @Transactional
    public void delete(Long id) { countryMapper.deleteById(id); }

    @Override
    @Transactional
    public SysCountry changeState(Long countryId, String newState, Long operatorId) {
        SysCountry country = countryMapper.selectById(countryId);
        if (country == null) {
            throw new BizException(bizCode(CODE_COUNTRY_NOT_FOUND, "国家不存在", 404));
        }

        String oldState = country.getStatus();
        if (Objects.equals(oldState, newState)) {
            log.debug("changeState idempotent: countryId={} state={}", countryId, newState);
            return country;
        }

        // 表驱动状态机校验：DRAFT→ACTIVE / DRAFT→VOID / ACTIVE→FROZEN / 等
        stateMachineEngine.assertTransition("sys_country", oldState, newState);

        SysCountry update = new SysCountry();
        update.setId(countryId);
        update.setStatus(newState);
        update.setVersion(country.getVersion());
        int rows = countryMapper.updateById(update);
        if (rows == 0) {
            throw new BizException(bizCode(CODE_COUNTRY_VERSION_CONFLICT, "国家状态更新版本冲突，请重试", 409));
        }

        // 事务内同步 insert outbox 行 + 同模块消费 publishEvent。
        // EventBus.publish 是 @Transactional(MANDATORY)，所以这里继承外部事务。
        // tenantId 用实体行的 tenantId（不是 TenantContext.get()）—— 这样事件审计字段
        // 反映"国家所属租户"，与请求发起的"操作人所在租户"解耦（运营平台跨租户场景）。
        eventBus.publish(new CountryStateChangedEvent(
                country.getTenantId(), countryId, oldState, newState, operatorId
        ));

        return countryMapper.selectById(countryId);
    }

    /** 构造一个匿名 ErrorCode（避免为这两个简单 case 单独建枚举）。 */
    private static ErrorCode bizCode(int code, String message, int httpStatus) {
        return new ErrorCode() {
            @Override public int getCode() { return code; }
            @Override public String getMessage() { return message; }
            @Override public int getHttpStatus() { return httpStatus; }
        };
    }
}