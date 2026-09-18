package com.lumen.common.util;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SensitiveMaskUtilTest {
    @Test void maskPassword() { assertThat(SensitiveMaskUtil.maskPassword("123456")).isEqualTo("******"); }
    @Test void maskMobile() { assertThat(SensitiveMaskUtil.maskMobile("13800001234")).isEqualTo("138****1234"); }
    @Test void maskToken() { assertThat(SensitiveMaskUtil.maskToken("abcdefghijklmn")).isEqualTo("abcd...klmn"); }
    @Test void maskMobile_nullReturnsMask() { assertThat(SensitiveMaskUtil.maskMobile(null)).isEqualTo("***"); }
    @Test void maskMobile_shortReturnsMask() { assertThat(SensitiveMaskUtil.maskMobile("12345")).isEqualTo("***"); }
    @Test void maskMobile_length7ReturnsMask() { assertThat(SensitiveMaskUtil.maskMobile("1234567")).isEqualTo("***"); }
    @Test void maskToken_nullReturnsMask() { assertThat(SensitiveMaskUtil.maskToken(null)).isEqualTo("***"); }
    @Test void maskToken_shortReturnsMask() { assertThat(SensitiveMaskUtil.maskToken("abc")).isEqualTo("***"); }
}
