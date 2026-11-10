package com.lumen.order.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderErrorCode 的 httpStatus 必须与 code 前缀一致 (4xx_1xx 家族).
 *
 * <p>这道防线的作用: 有人把某个码的 httpStatus 写错时, 客户端拿到的 HTTP 状态
 * 与 body.code 对不上, 前端会走进错误的分支。
 */
class OrderErrorCodeTest {

    @Test
    @DisplayName("每个错误码的 httpStatus 与 code 前三位一致")
    void httpStatusMatchesCodePrefix() {
        for (OrderErrorCode ec : OrderErrorCode.values()) {
            assertThat(ec.getHttpStatus())
                    .as("%s 的 httpStatus 应与 code 前缀一致", ec.name())
                    .isEqualTo(ec.getCode() / 1000);
        }
    }

    @Test
    @DisplayName("8 个错误码, code 互不重复")
    void codesUnique() {
        assertThat(OrderErrorCode.values()).hasSize(8);
        long distinct = java.util.Arrays.stream(OrderErrorCode.values())
                .map(OrderErrorCode::getCode).distinct().count();
        assertThat(distinct).isEqualTo(OrderErrorCode.values().length);
    }
}
