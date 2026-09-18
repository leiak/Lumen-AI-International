package com.lumen.common.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SensitiveMaskUtilTest {
    @Test void maskPassword() { assertThat(SensitiveMaskUtil.maskPassword("123456")).isEqualTo("******"); }
    @Test void maskMobile() { assertThat(SensitiveMaskUtil.maskMobile("13800001234")).isEqualTo("138****1234"); }
    @Test void maskToken() { assertThat(SensitiveMaskUtil.maskToken("abcdefghijklmn")).isEqualTo("abcd...klmn"); }
}
