package com.lumen.finance.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.dto.BillingEventLogDtos.View;
import com.lumen.finance.entity.BillingEventLog;
import com.lumen.finance.mapper.BillingEventLogMapper;
import com.lumen.finance.service.BillingEventLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * BillingEventLogService 实现 (Iteration 4.3.1, PR3 代码质量修复).
 *
 * <p>thin wrapper: 把 controller 里直接调 mapper 的逻辑上移到 service 层,
 * 保持 controller → service → mapper 分层一致 (与 BillingRuleService / AccrualService 同模式).
 */
@Service
@RequiredArgsConstructor
public class BillingEventLogServiceImpl implements BillingEventLogService {

    private final BillingEventLogMapper eventLogMapper;

    @Override
    public List<View> list(Long tenantId, String businessType, String processStatus,
                           Integer pageNum, Integer pageSize) {
        int pn = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int ps = (pageSize == null || pageSize < 1) ? 20 : Math.min(pageSize, 200);

        LambdaQueryWrapper<BillingEventLog> q = new LambdaQueryWrapper<BillingEventLog>()
            .eq(BillingEventLog::getTenantId, tenantId)
            .eq(businessType != null && !businessType.isBlank(),
                BillingEventLog::getBusinessType, businessType)
            .eq(processStatus != null && !processStatus.isBlank(),
                BillingEventLog::getProcessStatus, processStatus)
            .orderByDesc(BillingEventLog::getId);

        Page<BillingEventLog> p = new Page<>(pn, ps);
        Page<BillingEventLog> result = eventLogMapper.selectPage(p, q);
        List<BillingEventLog> records = result.getRecords();
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<View> views = new ArrayList<>(records.size());
        for (BillingEventLog e : records) {
            views.add(new View(
                e.getId(),
                e.getTenantId(),
                e.getSourceEventId(),
                e.getBusinessType(),
                e.getBusinessId(),
                e.getProcessStatus(),
                e.getErrorMsg(),
                e.getReceivedAt(),
                e.getProcessedAt()
            ));
        }
        return views;
    }
}