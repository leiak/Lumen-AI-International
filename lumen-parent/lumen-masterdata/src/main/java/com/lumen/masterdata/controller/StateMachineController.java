package com.lumen.masterdata.controller;

import com.lumen.common.api.R;
import com.lumen.common.state.StateMachineRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/state-machines")
@RequiredArgsConstructor
public class StateMachineController {
    private final StateMachineRegistry registry;

    @PostMapping("/{code}/fire")
    public R<Object> fire(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Object from = body.get("from");
        Object event = body.get("event");
        Object ctx = body.get("ctx");
        Object to = registry.get(code).fire(from, event, ctx);
        return R.ok(Map.of("from", from, "event", event, "to", to));
    }
}