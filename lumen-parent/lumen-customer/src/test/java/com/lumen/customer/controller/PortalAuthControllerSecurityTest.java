package com.lumen.customer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class PortalAuthControllerSecurityTest {

    @Test
    void me_isNotAnnotatedWithPreAuthorize_audEnforcedAtFilter() throws NoSuchMethodException {
        // /me is protected by JwtAuthenticationFilter audience routing + SecurityConfig's
        // .anyRequest().authenticated(), not by @PreAuthorize. Verify the absence.
        Method m = PortalAuthController.class.getMethod("me");
        PreAuthorize ann = m.getAnnotation(PreAuthorize.class);
        assertNull(ann, "PortalAuthController.me should NOT be @PreAuthorize (filter enforces aud=portal)");
    }

    @Test
    void commonProfile_isAnnotatedWithPreAuthorizeView() throws NoSuchMethodException {
        // /api/customer/profile (GET) requires customer:profile:view authority
        Method m = PortalCommonController.class.getMethod("profile");
        PreAuthorize ann = m.getAnnotation(PreAuthorize.class);
        assertNotNull(ann);
        assertTrue(ann.value().contains("customer:profile:view"));
    }
}