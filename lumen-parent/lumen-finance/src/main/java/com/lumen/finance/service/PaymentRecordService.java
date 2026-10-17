package com.lumen.finance.service;

import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface PaymentRecordService {
    BillingPaymentRecord get(Long tenantId, Long id);
    List<BillingPaymentRecord> list(Long tenantId, String period, String direction, int page, int size);
    BillingPaymentRecord create(Long tenantId, Long userId, CreateRequest req);
    ImportResult importCsv(Long tenantId, String period, Long userId, MultipartFile file);
}