package com.lumen.common.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BizExceptionTest {
    @Test
    void bizException_carriesErrorCode() {
        BizException ex = BizException.of(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo("资源不存在");
    }

    @Test
    void bizException_supportsCustomMessage() {
        BizException ex = new BizException(CommonErrorCode.NOT_FOUND, "用户 100 不存在");
        assertThat(ex.getMessage()).isEqualTo("用户 100 不存在");
    }
}