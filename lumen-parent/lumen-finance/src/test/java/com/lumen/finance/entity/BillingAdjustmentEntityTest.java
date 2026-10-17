package com.lumen.finance.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BillingAdjustmentEntityTest {

    @Test
    void builder_populatesAllFields() {
        LocalDateTime now = LocalDateTime.of(2026, 1, 15, 12, 0, 0);
        BillingAdjustment adj = BillingAdjustment.builder()
            .id(1L)
            .tenantId(100L)
            .adjustmentNo("ADJ-2026-0001")
            .adjustmentType("CREDIT")
            .amount(new BigDecimal("100.50"))
            .currency("CNY")
            .status("PENDING_L1")
            .l1ApproverId(11L)
            .l1ApprovedAt(now)
            .l1Comment("L1 ok")
            .l2ApproverId(22L)
            .l2ApprovedAt(now)
            .l2Comment("L2 ok")
            .errorMsg(null)
            .createdAt(now)
            .updatedAt(now)
            .build();
        assertEquals(1L, adj.getId());
        assertEquals(100L, adj.getTenantId());
        assertEquals("ADJ-2026-0001", adj.getAdjustmentNo());
        assertEquals("CREDIT", adj.getAdjustmentType());
        assertEquals(new BigDecimal("100.50"), adj.getAmount());
        assertEquals("CNY", adj.getCurrency());
        assertEquals("PENDING_L1", adj.getStatus());
        assertEquals(11L, adj.getL1ApproverId());
        assertEquals(now, adj.getL1ApprovedAt());
        assertEquals("L1 ok", adj.getL1Comment());
        assertEquals(22L, adj.getL2ApproverId());
        assertEquals(now, adj.getL2ApprovedAt());
        assertEquals("L2 ok", adj.getL2Comment());
        assertEquals(null, adj.getErrorMsg());
        assertEquals(now, adj.getCreatedAt());
        assertEquals(now, adj.getUpdatedAt());
    }

    @Test
    void noArgsConstructor_createsEmptyInstance() {
        BillingAdjustment adj = new BillingAdjustment();
        assertNotNull(adj);
    }
}
