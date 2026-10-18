package com.lumen.finance.controller;

import com.lumen.common.tenant.TenantContext;
import com.lumen.finance.dto.ReportDtos.ExportRequest;
import com.lumen.finance.dto.ReportDtos.ReportType;
import com.lumen.finance.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 报表导出 REST 控制器 (Iteration 4.4, Task 2.4).
 *
 * <p>路径 {@code /api/v1/finance/reports}; 3 个 GET 端点 (summary + detail + variance),
 * 均返回 {@code application/vnd.openxmlformats-officedocument.spreadsheetml.sheet}
 * 二进制 (Apache POI {@code SXSSFWorkbook}, see {@code ReportServiceImpl}).
 *
 * <p>权限: {@code finance:report:view} — 3 个端点共用同一权限 (与 V30 RBAC seed 一致;
 * PR2.3 时为 {@code :export}, 4.4 controller 层统一改名为 {@code :view} 以贴合前端
 * "看报表" 的真实诉求).
 *
 * <p>共用查询参数 {@code period} (必填, {@code YYYY-MM}) + {@code currency} (必填,
 * 3 字符 ISO 4217). Service 层 {@link ReportService#export} 完成校验 + 数据集生成.
 */
@RestController
@RequestMapping("/api/v1/finance/reports")
@RequiredArgsConstructor
public class BillingReportController {

    /** Excel content type — XLSX (Office Open XML SpreadsheetML). */
    private static final String XLSX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportService reportService;

    /**
     * 导出汇总报表 — 期间内 invoice / accrual / adjustment 金额合计.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('finance:report:view')")
    public ResponseEntity<byte[]> summary(@RequestParam String period,
                                          @RequestParam String currency) {
        return buildResponse(ReportType.SUMMARY, period, currency);
    }

    /**
     * 导出明细报表 — 期间内 {@code L2_APPROVED} 状态发票明细.
     */
    @GetMapping("/detail")
    @PreAuthorize("hasAuthority('finance:report:view')")
    public ResponseEntity<byte[]> detail(@RequestParam String period,
                                         @RequestParam String currency) {
        return buildResponse(ReportType.DETAIL, period, currency);
    }

    /**
     * 导出差异报表 — 期间内对账记录 (variance / status / adjustment id).
     */
    @GetMapping("/variance")
    @PreAuthorize("hasAuthority('finance:report:view')")
    public ResponseEntity<byte[]> variance(@RequestParam String period,
                                           @RequestParam String currency) {
        return buildResponse(ReportType.VARIANCE, period, currency);
    }

    /**
     * 构造报表二进制响应. 文件名按报表类型小写 + 期间命名, UTF-8 编码避免非 ASCII 期间号丢字.
     */
    private ResponseEntity<byte[]> buildResponse(ReportType type, String period, String currency) {
        Long tenantId = TenantContext.require();
        ExportRequest req = new ExportRequest();
        req.setPeriod(period);
        req.setCurrency(currency);
        req.setType(type);
        byte[] xlsx = reportService.export(req, tenantId);

        // 文件名: {type-lowercase}-{period}.xlsx (currency 不进文件名, 已在 sheet 内容中可见)
        String filename = type.name().toLowerCase() + "-" + period + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(XLSX_CONTENT_TYPE));
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encoded + "\"");
        return ResponseEntity.ok().headers(headers).body(xlsx);
    }
}
