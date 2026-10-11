package com.lumen.wms.controller;

import com.lumen.common.api.R;
import com.lumen.wms.dto.UpdateThresholdConfigRequest;
import com.lumen.wms.entity.WmsThresholdConfig;
import com.lumen.wms.service.ThresholdConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WMS 审批阈值配置 REST 控制器.
 *
 * <p>本控制器仅承担配置读写;真正的阈值评估由
 * {@code com.lumen.wms.approval.ThresholdEvaluator} 在业务流中调用.
 */
@RestController
@RequestMapping("/api/v1/wms/threshold")
@RequiredArgsConstructor
public class ThresholdConfigController {

    private final ThresholdConfigService svc;

    @GetMapping
    @PreAuthorize("hasAuthority('wms:threshold:get')")
    public R<WmsThresholdConfig> get() {
        return R.ok(svc.getOrInit());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('wms:threshold:update')")
    public R<WmsThresholdConfig> update(@Valid @RequestBody UpdateThresholdConfigRequest req) {
        return R.ok(svc.update(req));
    }
}
