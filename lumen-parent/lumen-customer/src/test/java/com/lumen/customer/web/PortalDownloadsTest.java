package com.lumen.customer.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PortalDownloads — xlsx 下载响应头")
class PortalDownloadsTest {

    @Test
    @DisplayName("Content-Type 是 xlsx, 长度对, 中文文件名按 UTF-8 编码进 Content-Disposition")
    void xlsxHeaders() {
        byte[] body = new byte[]{1, 2, 3};
        ResponseEntity<byte[]> resp = PortalDownloads.xlsx("对账单.xlsx", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isSameAs(body);
        assertThat(resp.getHeaders().getContentType().toString())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resp.getHeaders().getContentLength()).isEqualTo(3);

        ContentDisposition cd = resp.getHeaders().getContentDisposition();
        assertThat(cd).isNotNull();
        assertThat(cd.getType()).isEqualTo("attachment");
        // getFilename() 返回**解码后**的文件名 —— 中文没变成乱码才算过
        assertThat(cd.getFilename()).contains("对账单.xlsx");
    }
}
