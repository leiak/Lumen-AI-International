package com.lumen.common.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RTest {
    @Test
    void ok_createsSuccessR() {
        R<String> r = R.ok("hello");
        assertThat(r.getCode()).isEqualTo(0);
        assertThat(r.getMessage()).isEqualTo("success");
        assertThat(r.getData()).isEqualTo("hello");
        assertThat(r.isSuccess()).isTrue();
    }

    @Test
    void fail_createsFailureR() {
        R<String> r = R.fail(404, "not found");
        assertThat(r.getCode()).isEqualTo(404);
        assertThat(r.getMessage()).isEqualTo("not found");
        assertThat(r.getData()).isNull();
        assertThat(r.isSuccess()).isFalse();
    }
}