package com.lumen.cs;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CsConstantsTest {
    @Test
    void slaEventTypeL1ResponseDue_isStable() {
        assertThat(CsConstants.SLA_EVENT_L1_RESPONSE_DUE).isEqualTo("L1_RESPONSE_DUE");
    }
}