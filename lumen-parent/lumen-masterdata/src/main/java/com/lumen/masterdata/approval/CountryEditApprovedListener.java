package com.lumen.masterdata.approval;

import cn.hutool.json.JSONUtil;
import com.lumen.common.tenant.TenantContext;
import com.lumen.extension.outbox.events.CountryEditApprovedEvent;
import com.lumen.masterdata.entity.SysCountry;
import com.lumen.masterdata.mapper.SysCountryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费 {@link CountryEditApprovedEvent}，把审批通过的变更应用到 sys_country。
 *
 * <p>放在 {@code lumen-masterdata} 而不是 {@code lumen-extension}：
 * 消费事件 + 调用 masterdata mapper，如果放 extension 就要 extension 反向依赖
 * masterdata，违反 §2.3 "lumen-extension 不依赖任何 lumen-* module"。
 * 反过来 masterdata 依赖 extension 是允许的。
 *
 * <p>{@code @Async("outboxExecutor")} 让 listener 跑在 outbox 线程池上，
 * 不阻塞 outbox dispatcher 主循环。{@code outboxExecutor} 线程的
 * {@link TenantContext} ThreadLocal 是空的（dispatcher 路径不携带）；
 * 这里从事件本身取 {@code tenantId} 设到上下文，保证
 * {@code sys_country} 多租户拦截器能命中正确的租户行 —— 不然
 * {@code countryMapper.updateById} 会加 {@code AND tenant_id = 0}，
 * 而生产环境的 country 可能是任意 tenant_id，导致 0 行被更新。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CountryEditApprovedListener {
    private final SysCountryMapper countryMapper;

    @Async("outboxExecutor")
    @EventListener
    public void on(CountryEditApprovedEvent event) {
        try {
            TenantContext.set(event.getTenantId());
            Map<String, Object> changes = JSONUtil.toBean(event.getSnapshot(), Map.class);
            SysCountry update = new SysCountry();
            update.setId(event.getCountryId());
            // 仅应用允许的字段（nameCn / nameEn）；id/tenantId/version 不允许走审批通道改。
            // status 变更必须走 C2 状态机（SysCountryServiceImpl.changeState + t_state_transition），
            // 审批通道直接 setStatus 等于绕过状态机非法迁移 —— 这是
            // ApprovalFlowIT#scenario9b_payloadStatusInjection_isIgnoredByListener 暴露的 bug。
            if (changes.containsKey("nameCn")) update.setNameCn((String) changes.get("nameCn"));
            if (changes.containsKey("nameEn")) update.setNameEn((String) changes.get("nameEn"));
            int rows = countryMapper.updateById(update);
            log.info("Country {} updated by approval: {} rows", event.getCountryId(), rows);
        } finally {
            TenantContext.clear();
        }
    }
}
