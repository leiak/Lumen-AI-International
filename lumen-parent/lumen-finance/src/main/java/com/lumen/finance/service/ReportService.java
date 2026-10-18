package com.lumen.finance.service;

import com.lumen.finance.dto.ReportDtos.ExportRequest;

public interface ReportService {
    byte[] export(ExportRequest req, Long tenantId);
}