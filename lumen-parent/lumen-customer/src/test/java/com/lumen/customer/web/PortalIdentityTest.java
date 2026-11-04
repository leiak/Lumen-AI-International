package com.lumen.customer.web;

import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PortalIdentity — 身份只能来自 JWT, 不来自请求体")
class PortalIdentityTest {

    @AfterEach
    void tearDown() {
        CustomerContext.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("counterpartyId 取自 CustomerContext (商家 id)")
    void counterpartyId() {
        CustomerContext.set(77L, 42L);
        assertThat(PortalIdentity.requireCounterpartyId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("userId 取自 CustomerContext (登录用户 id)")
    void userId() {
        CustomerContext.set(77L, 42L);
        assertThat(PortalIdentity.requireUserId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("tenantId 取自 TenantContext (JWT 的 tid claim)")
    void tenantId() {
        TenantContext.set(9L);
        assertThat(PortalIdentity.requireTenantId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("身份缺失 → 抛 CustomerException (三个入口都是)")
    void missingIdentityThrows() {
        assertThatThrownBy(PortalIdentity::requireCounterpartyId).isInstanceOf(CustomerException.class);
        assertThatThrownBy(PortalIdentity::requireUserId).isInstanceOf(CustomerException.class);
        assertThatThrownBy(PortalIdentity::requireTenantId).isInstanceOf(CustomerException.class);
    }
}
