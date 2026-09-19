package com.lumen.masterdata.approval;

import cn.hutool.json.JSONUtil;
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
 * 不阻塞 outbox dispatcher 主循环；listener 内部 updateById 是独立事务，
 * 没有外层事务包住（{@code SysCountryMapper.updateById} 单条 update 自身
 * 即可提交）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CountryEditApprovedListener {
    private final SysCountryMapper countryMapper;

    @Async("outboxExecutor")
    @EventListener
    public void on(CountryEditApprovedEvent event) {
        Map<String, Object> changes = JSONUtil.toBean(event.getSnapshot(), Map.class);
        SysCountry update = new SysCountry();
        update.setId(event.getCountryId());
        // 仅应用允许的字段（nameCn / nameEn / status）；id/tenantId/version 不允许走审批通道改
        if (changes.containsKey("nameCn")) update.setNameCn((String) changes.get("nameCn"));
        if (changes.containsKey("nameEn")) update.setNameEn((String) changes.get("nameEn"));
        if (changes.containsKey("status")) update.setStatus((String) changes.get("status"));
        int rows = countryMapper.updateById(update);
        log.info("Country {} updated by approval: {} rows", event.getCountryId(), rows);
    }
}
