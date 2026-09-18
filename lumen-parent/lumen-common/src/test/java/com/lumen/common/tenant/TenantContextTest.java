package com.lumen.common.tenant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TenantContextTest {
    @Test
    void set_and_get_roundtrip() {
        TenantContext.set(42L);
        assertThat(TenantContext.get()).isEqualTo(42L);
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void require_throws_when_unset() {
        TenantContext.clear();
        assertThatThrownBy(TenantContext::require).isInstanceOf(IllegalStateException.class);
    }
}