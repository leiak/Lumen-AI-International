package com.lumen.finance.service;

import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.service.impl.PaymentRecordServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRecordServiceTest {

    @Mock private com.lumen.finance.mapper.BillingPaymentRecordMapper mapper;
    @InjectMocks private PaymentRecordServiceImpl service;

    @Test
    void create_happy_persistsRecord() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-10");
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000.00"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));
        req.setSourceRef("BANK20261015001");
        req.setRemark("test");

        when(mapper.insert(any(BillingPaymentRecord.class))).thenReturn(1);

        BillingPaymentRecord result = service.create(1L, 999L, req);

        assertNotNull(result);
        assertEquals("MANUAL", result.getSource());
        assertEquals(999L, result.getCreatedBy());
        verify(mapper).insert(any(BillingPaymentRecord.class));
    }

    @Test
    void create_invalidPeriod_throwsBadRequest() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-13");  // invalid month
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.create(1L, 999L, req));
        // Should be BAD_REQUEST (not PAYMENT_RECORD_DUPLICATE) for malformed period
        assertEquals(FinanceErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void create_duplicateKey_throwsPaymentRecordDuplicate() {
        CreateRequest req = new CreateRequest();
        req.setPeriod("2026-10");
        req.setDirection("RECEIVABLE");
        req.setCounterpartyId(100L);
        req.setCounterpartyType("CUSTOMER");
        req.setCurrency("CNY");
        req.setAmount(new BigDecimal("1000"));
        req.setPaymentDate(LocalDate.of(2026, 10, 15));

        when(mapper.insert(any(BillingPaymentRecord.class)))
            .thenThrow(new DuplicateKeyException("uk_payment"));

        FinanceException ex = assertThrows(FinanceException.class,
            () -> service.create(1L, 999L, req));
        assertEquals(FinanceErrorCode.PAYMENT_RECORD_DUPLICATE, ex.getErrorCode());
    }

    @Test
    void importCsv_happyCountsSuccessAndSkipsDuplicate() {
        String csv = "direction,counterparty_id,counterparty_type,currency,amount,payment_date,source_ref,remark\n"
                   + "RECEIVABLE,100,CUSTOMER,CNY,500.00,2026-10-15,BANK001,ok\n"
                   + "RECEIVABLE,101,CUSTOMER,CNY,600.00,2026-10-15,BANK002,ok\n";
        MultipartFile file = new MockMultipartFile("f", "test.csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));

        when(mapper.insert(any(BillingPaymentRecord.class)))
            .thenReturn(1)
            .thenThrow(new DuplicateKeyException("uk_payment"));

        ImportResult result = service.importCsv(1L, "2026-10", 999L, file);

        assertEquals(2, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkippedCount());
    }
}