package com.lumen.customer.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * 门户文件下载响应 (Iteration 4.5C-2).
 *
 * <p>唯一职责: 把 byte[] 包成带正确下载头的 {@code ResponseEntity}。
 * 2 个导出端点共用, 免得两处各自拼一遍 Content-Disposition 而其中一个忘了
 * UTF-8 转义 —— 那会让中文文件名在浏览器里变成乱码。
 */
public final class PortalDownloads {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private PortalDownloads() {
    }

    /**
     * @param filename 建议的文件名, 可含中文
     * @param body     POI 生成的 xlsx 字节
     */
    public static ResponseEntity<byte[]> xlsx(String filename, byte[] body) {
        HttpHeaders h = new HttpHeaders();
        // RFC 5987: filename* 存 UTF-8 百分号编码; filename 保留 ASCII 兜底给老客户端
        h.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        h.setContentType(XLSX);
        h.setContentLength(body.length);
        return new ResponseEntity<>(body, h, HttpStatus.OK);
    }
}
