package com.lumen.finance.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumen.finance.dto.PaymentRecordDtos.CreateRequest;
import com.lumen.finance.dto.PaymentRecordDtos.ImportResult;
import com.lumen.finance.entity.BillingPaymentRecord;
import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;
import com.lumen.finance.service.impl.PaymentRecordServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRecordServiceTest {

    @Mock private com.lumen.finance.mapper.BillingPaymentRecordMapper mapper;
    @InjectMocks private PaymentRecordServiceImpl service;

    /**
     * MP 的 lambda 列名缓存由 Spring 启动时的 TableInfoHelper 填充; 纯 Mockito
     * 单测没有 Spring 上下文, 缓存是空的 —— 任何 eq(Entity::getX, v) 在生成
     * SQL 片段时都会抛 "can not find lambda cache for this entity"。
     * 没有这一步, 连 getSqlSegment() 都跑不到, 测试会在断言之前就炸。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BillingPaymentRecord.class);
    }

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

    // ---------- 4.5C-2.1 — 缺口 #8 ----------

    @Test
    @DisplayName("get: 查不到 → PAYMENT_RECORD_NOT_FOUND (404_007), 不是通用 NOT_FOUND (404_005)")
    void get_notFound_throwsPaymentRecordNotFound() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        FinanceException ex = assertThrows(FinanceException.class, () -> service.get(1L, 999L));

        assertEquals(FinanceErrorCode.PAYMENT_RECORD_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("get: 查询条件含 id + tenant_id + deleted = 0")
    void get_filtersByIdTenantAndDeleted() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(FinanceException.class, () -> service.get(7L, 999L));

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(cap.capture());
        LambdaQueryWrapper<BillingPaymentRecord> w = cap.getValue();
        // 先物化 SQL 片段 —— MP 的参数 map 是懒生成的, 不先调 getSqlSegment()
        // 就读 getParamNameValuePairs() 拿到的是空 map, 断言会变成恒真。
        assertThat(w.getSqlSegment()).contains("id").contains("tenant_id").contains("deleted = 0");
        assertThat(w.getParamNameValuePairs().values()).contains(999L, 7L);
    }

    @Test
    @DisplayName("list: 查询条件含 tenant_id 与 deleted = 0")
    void list_filtersByTenantAndDeleted() {
        when(mapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<>());

        service.list(7L, null, null, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<BillingPaymentRecord>> cap =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), cap.capture());
        LambdaQueryWrapper<BillingPaymentRecord> w = cap.getValue();
        assertThat(w.getSqlSegment()).contains("tenant_id").contains("deleted = 0");
        assertThat(w.getParamNameValuePairs().values()).contains(7L);
    }
}