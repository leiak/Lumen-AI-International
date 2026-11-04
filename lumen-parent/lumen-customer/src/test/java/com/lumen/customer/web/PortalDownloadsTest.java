package com.lumen.customer.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
        // getFilename() 返回的是**解码后**的值 —— 单参 filename() 写出的裸 UTF-8 头
        // 也能解出同一个字符串, 所以光断言它证明不了线上带的是 RFC 5987。
        // 必须再钉一层: header 里真的有 filename*=UTF-8'' + 百分号编码。
        assertThat(cd.getFilename()).contains("对账单.xlsx");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename*=UTF-8''")
                .contains("%E5%AF%B9%E8%B4%A6%E5%8D%95");   // "对账单" 的 UTF-8 百分号编码
    }
}
