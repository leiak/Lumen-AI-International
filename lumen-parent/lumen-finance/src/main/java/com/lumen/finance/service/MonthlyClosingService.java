package com.lumen.finance.service;

import com.lumen.finance.dto.ClosingDtos.*;
import com.lumen.finance.entity.BillingMonthlyClosing;
import java.util.List;

public interface MonthlyClosingService {
    BillingMonthlyClosing get(Long tenantId, String period);
    BillingMonthlyClosing getOrCreate(Long tenantId, String period);
    BillingMonthlyClosing start(Long tenantId, String period);
    BillingMonthlyClosing close(Long tenantId, String period);
    BillingMonthlyClosing reopen(Long tenantId, String period, String reason);
    BillingMonthlyClosing recalculateSnapshot(Long tenantId, String period);
    List<BillingMonthlyClosing> list(Long tenantId, int page, int size);
}
