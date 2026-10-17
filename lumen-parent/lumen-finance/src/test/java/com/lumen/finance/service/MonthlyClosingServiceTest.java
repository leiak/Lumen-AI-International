package com.lumen.finance.service;

import com.lumen.finance.entity.BillingMonthlyClosing;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.mapper.*;
import com.lumen.finance.engine.SnapshotCalculator;
import com.lumen.finance.service.impl.MonthlyClosingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyClosingServiceTest {

    @Mock private BillingMonthlyClosingMapper closingMapper;
    @Mock private BillingInvoiceMapper invoiceMapper;
    @Mock private BillingAccrualMapper accrualMapper;
    @Mock private BillingAdjustmentMapper adjustmentMapper;
    @Mock private SnapshotCalculator snapshotCalculator;
    @InjectMocks private MonthlyClosingServiceImpl service;

    @Test
    void getOrCreate_firstTime_createsOpen() {
        when(closingMapper.selectOne(any())).thenReturn(null);
        when(closingMapper.insert(any(BillingMonthlyClosing.class))).thenReturn(1);

        BillingMonthlyClosing c = service.getOrCreate(1L, "2026-10");

        assertEquals("OPEN", c.getStatus());
        assertEquals("CNY", c.getSnapshotCurrency());
        verify(closingMapper).insert(any(BillingMonthlyClosing.class));
    }

    @Test
    void getOrCreate_existing_returnsExisting() {
        BillingMonthlyClosing existing = BillingMonthlyClosing.builder()
            .id(1L).period("2026-10").status("IN_PROGRESS").build();
        when(closingMapper.selectOne(any())).thenReturn(existing);

        BillingMonthlyClosing c = service.getOrCreate(1L, "2026-10");

        assertEquals("IN_PROGRESS", c.getStatus());
        verify(closingMapper, never()).insert(any(BillingMonthlyClosing.class));
    }

    @Test
    void start_openToInProgress_recalculatesSnapshot() {
        BillingMonthlyClosing existing = BillingMonthlyClosing.builder()
            .id(1L).tenantId(1L).period("2026-10").status("OPEN").build();
        when(closingMapper.selectOne(any())).thenReturn(existing);
        when(invoiceMapper.selectList(any())).thenReturn(java.util.List.of());
        when(snapshotCalculator.empty()).thenReturn(
            new SnapshotCalculator.SnapshotResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        BillingMonthlyClosing result = service.start(1L, "2026-10");

        assertEquals("IN_PROGRESS", result.getStatus());
        verify(closingMapper).updateById(any(BillingMonthlyClosing.class));
    }

    @Test
    void start_notOpen_throwsMonthNotInProgress() {
        BillingMonthlyClosing existing = BillingMonthlyClosing.builder()
            .id(1L).period("2026-10").status("IN_PROGRESS").build();
        when(closingMapper.selectOne(any())).thenReturn(existing);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.start(1L, "2026-10"));
        assertEquals(FinanceErrorCode.MONTH_NOT_IN_PROGRESS.getCode(), ex.getErrorCode().getCode());
    }

    @Test
    void reopen_tooShortReason_throwsBadRequest() {
        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.reopen(1L, "2026-10", "short"));
        assertEquals(FinanceErrorCode.BAD_REQUEST.getCode(), ex.getErrorCode().getCode());
    }

    @Test
    void get_notFound_throwsMonthNotFound() {
        when(closingMapper.selectOne(any())).thenReturn(null);

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.get(1L, "2026-10"));
        assertEquals(FinanceErrorCode.MONTH_NOT_FOUND.getCode(), ex.getErrorCode().getCode());
    }
}
