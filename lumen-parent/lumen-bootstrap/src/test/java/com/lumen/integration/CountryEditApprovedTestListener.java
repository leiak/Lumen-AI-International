package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryEditApprovedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 顶层 {@code @Component} 监听 CountryEditApprovedEvent。
 * 与 {@link OutboxTestListener} 同源考量，避免 nested @Component + @Import
 * 在 @SpringBootTest 里 @EventListener 偶发漏注册。
 *
 * <p>本测试只关心"事件有没有被 outbox dispatcher 投递到 ApplicationContext"，
 * 实际把变更应用到 sys_country 的工作由生产代码
 * {@code CountryEditApprovedListener} 完成（也是 @Async 跑的）。
 */
@Component
public class CountryEditApprovedTestListener {

    private final List<CountryEditApprovedEvent> received = new CopyOnWriteArrayList<>();

    public void clear() {
        received.clear();
    }

    public List<CountryEditApprovedEvent> getReceived() {
        return received;
    }

    @EventListener
    public void on(CountryEditApprovedEvent e) {
        received.add(e);
    }
}
