package com.lumen.cs.scheduler;

import com.lumen.cs.service.EscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLA 升级扫描调度器 (Iteration 4.2 PR4).
 *
 * <p>默认每 5 分钟扫一次, 间隔可由 {@code lumen.cs.sla-scan-interval-ms}
 * 配置覆盖. {@code initialDelay=60_000} 让 Spring 容器启动后 1 分钟才
 * 开始第一次扫, 避免和 OutboxDispatcher / 业务启动争抢资源.
 *
 * <p>{@link Scheduled} 间隔触发 {@link EscalationService#scan()}; 异常
 * 被吞掉 (scan() 内部有 try/catch + log.error), 不会污染下一次 tick.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaEscalationScheduler {

    private final EscalationService escalationService;

    @Scheduled(
        fixedDelayString = "${lumen.cs.sla-scan-interval-ms:300000}",
        initialDelay = 60_000
    )
    public void tick() {
        try {
            escalationService.scan();
        } catch (Exception e) {
            // 兜底: 即使 scan 内部 try/catch 漏掉了某个 throwable, 也不应
            // 让 scheduler 挂掉. 异常吞掉, 下个 tick 再扫.
            log.error("SLA escalation scan failed", e);
        }
    }
}
