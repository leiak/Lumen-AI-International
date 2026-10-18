package com.lumen.customer.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.common.tenant.TenantContext;
import com.lumen.customer.exception.CustomerException;
import com.lumen.customer.security.CustomerContext;
import com.lumen.dispatch.service.DeliveryOrderService;
import com.lumen.wms.service.PodService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PortalTrackControllerTest {

    @Mock DeliveryOrderService deliveryOrderService;
    @Mock PodService podService;
    @InjectMocks PortalTrackController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.set(1L);
        CustomerContext.set(10L, 20L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CustomerContext.clear();
    }

    @Test
    void list_callsServiceWithTenantAndCustomerId() {
        // Build a real (empty) IPage so the controller's p.getRecords().stream() doesn't NPE.
        when(deliveryOrderService.pageForCustomer(anyLong(), anyLong(), any()))
            .thenReturn(Page.of(1, 20, 0));

        var resp = controller.list(null, null, 1, 20);
        assertNotNull(resp);
        assertNotNull(resp.getData());
        assertEquals(0L, resp.getData().get("total"));
        // verify the service was called with tenantId=1 and customerId=20 (from CustomerContext)
        verify(deliveryOrderService).pageForCustomer(eq(1L), eq(20L), any());
    }

    @Test
    void detail_callsServiceAndReturnsTypedDetail() {
        // detail returns R<PortalTrackDetail> — service returns a Map; controller converts
        Map<String, Object> raw = new HashMap<>();
        raw.put("doId", 100);
        raw.put("doNo", "DO-001");
        raw.put("status", "SIGNED");
        raw.put("originAddress", "Addr A");
        raw.put("destAddress", "Addr B");
        raw.put("recipientName", "Bob");
        raw.put("recipientPhone", "13800000000");
        raw.put("weight", "12.5");
        raw.put("volume", "0.5");
        raw.put("createdAt", "2026-10-01T10:00:00");
        raw.put("signedAt", "2026-10-02T14:30:00");
        raw.put("timeline", List.of());
        when(deliveryOrderService.getDetailForCustomer(anyLong(), anyLong(), anyLong()))
            .thenReturn(raw);

        var resp = controller.detail(100L);
        assertNotNull(resp);
        assertNotNull(resp.getData());
        assertEquals("DO-001", resp.getData().doNo());
        assertEquals("Bob", resp.getData().recipientName());
    }

    @Test
    void list_withoutCustomerContext_throwsCustomerException() {
        CustomerContext.clear();
        assertThrows(CustomerException.class, () -> controller.list(null, null, 1, 20));
    }
}