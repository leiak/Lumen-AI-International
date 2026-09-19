package com.lumen.integration;

import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 顶层 {@code @Component} 监听 CountryStateChangedEvent。与
 * {@link OutboxTestListener} 同源考量，避免 nested @Component + @Import
 * 在 @SpringBootTest 里 @EventListener 偶发漏注册。
 */
@Component
public class CountryStateChangeListener {

    private final List<CountryStateChangedEvent> received = new CopyOnWriteArrayList<>();

    public void clear() {
        received.clear();
    }

    public List<CountryStateChangedEvent> getReceived() {
        return received;
    }

    @EventListener
    public void on(CountryStateChangedEvent e) {
        received.add(e);
    }
}
